package com.noxsip.app.data

import android.content.Context
import com.noxsip.app.data.models.CallRecord
import com.noxsip.app.data.models.SavedContact
import com.noxsip.app.data.models.SipAccountData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * অ্যাকাউন্ট, কল লগ ও কন্টাক্টের এনক্রিপ্টেড ব্যাকআপ/রিস্টোর (কল রেকর্ডিং নয়)।
 * ফাইল: "SIPBK1" + salt(16) + iv(12) + AES-256-GCM(gzip(JSON)); কী = PBKDF2-HMAC-SHA256(পাসফ্রেজ)।
 * পাসফ্রেজ ছাড়া ফাইল খোলা যায় না, তাই পাসওয়ার্ড নিরাপদ থাকে।
 */
object BackupManager {
    private val MAGIC = "SIPBK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 150_000
    private const val FORMAT_VERSION = 1

    data class Summary(val accounts: Int, val calls: Int, val contacts: Int = 0)

    class WrongPassphraseException : Exception("Wrong passphrase or corrupted backup file")

    private fun deriveKey(pass: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    suspend fun export(context: Context, passphrase: CharArray, out: OutputStream): Summary =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val accounts = AccountRepository(context).getAccounts().first()
            val calls = db.callHistoryDao().getAllOnce()

            val root = JSONObject().put("version", FORMAT_VERSION)
            root.put("accounts", JSONArray().also { arr ->
                accounts.forEach { a ->
                    arr.put(
                        JSONObject()
                            .put("id", a.id).put("name", a.name)
                            .put("username", a.username).put("password", a.password)
                            .put("domain", a.domain).put("port", a.port)
                            .put("transport", a.transport).put("proxy", a.proxy)
                            .put("stunServer", a.stunServer).put("turnUsername", a.turnUsername)
                            .put("turnPassword", a.turnPassword)
                            .put("displayName", a.displayName).put("autoStart", a.autoStart)
                    )
                }
            })
            root.put("calls", JSONArray().also { arr ->
                calls.forEach { c ->
                    arr.put(
                        JSONObject()
                            .put("a", c.remoteAddress).put("n", c.displayName)
                            .put("in", c.isIncoming).put("ans", c.isAnswered)
                            .put("d", c.duration).put("t", c.timestamp).put("u", c.accountUser)
                    )
                }
            })
            val savedContacts = db.savedContactDao().getAllOnce()
            root.put("contacts", JSONArray().also { arr ->
                savedContacts.forEach { c ->
                    arr.put(
                        JSONObject()
                            .put("cat", c.category).put("n", c.name).put("num", c.number)
                            .put("co", c.company).put("em", c.email).put("ad", c.address)
                            .put("bd", c.birthday).put("nt", c.notes)
                            .put("wa", c.hasWhatsapp).put("tg", c.hasTelegram).put("t", c.createdAt)
                    )
                }
            })

            val gz = ByteArrayOutputStream()
            GZIPOutputStream(gz).use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }

            val rnd = SecureRandom()
            val salt = ByteArray(16).also { rnd.nextBytes(it) }
            val iv = ByteArray(12).also { rnd.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(gz.toByteArray())

            out.write(MAGIC)
            out.write(salt)
            out.write(iv)
            out.write(encrypted)
            out.flush()
            Summary(accounts.size, calls.size, savedContacts.size)
        }

    suspend fun restore(context: Context, passphrase: CharArray, input: InputStream): Summary =
        withContext(Dispatchers.IO) {
            val all = input.readBytes()
            if (all.size < MAGIC.size + 28 || !all.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw IllegalArgumentException("This is not a Nox SIP backup file")
            }
            val salt = all.copyOfRange(MAGIC.size, MAGIC.size + 16)
            val iv = all.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
            val body = all.copyOfRange(MAGIC.size + 28, all.size)

            val json = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
                val plain = cipher.doFinal(body)
                GZIPInputStream(ByteArrayInputStream(plain)).use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (e: Exception) {
                throw WrongPassphraseException()
            }
            val root = JSONObject(json)

            // অ্যাকাউন্ট
            val accounts = mutableListOf<SipAccountData>()
            root.optJSONArray("accounts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    accounts.add(
                        SipAccountData(
                            id = o.optString("id"), name = o.optString("name"),
                            username = o.optString("username"), password = o.optString("password"),
                            domain = o.optString("domain"), port = o.optInt("port", 5060),
                            transport = o.optString("transport", "UDP"), proxy = o.optString("proxy"),
                            stunServer = o.optString("stunServer"), turnUsername = o.optString("turnUsername"),
                            turnPassword = o.optString("turnPassword"),
                            displayName = o.optString("displayName"), autoStart = o.optBoolean("autoStart", false)
                        )
                    )
                }
            }
            val accCount = AccountRepository(context).importAccounts(accounts)

            val db = AppDatabase.getInstance(context)

            // কল লগ (একই সময়+নম্বর থাকলে বাদ)
            var callCount = 0
            val callDao = db.callHistoryDao()
            root.optJSONArray("calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val addr = o.optString("a")
                    val ts = o.optLong("t")
                    if (callDao.countSame(ts, addr) > 0) continue
                    callDao.insertCallRecord(
                        CallRecord(
                            remoteAddress = addr, displayName = o.optString("n"),
                            isIncoming = o.optBoolean("in"), isAnswered = o.optBoolean("ans", true),
                            duration = o.optInt("d"), timestamp = ts,
                            accountUser = o.optString("u", "")
                        )
                    )
                    callCount++
                }
            }

            // Client / Office কন্টাক্ট (একই ক্যাটাগরি+নম্বর থাকলে বাদ)
            var contactCount = 0
            val contactDao = db.savedContactDao()
            root.optJSONArray("contacts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val cat = o.optString("cat", SavedContact.CATEGORY_CLIENT)
                    val num = o.optString("num")
                    if (num.isBlank() || contactDao.countSame(cat, num) > 0) continue
                    contactDao.insert(
                        SavedContact(
                            category = cat, name = o.optString("n"), number = num,
                            company = o.optString("co"), email = o.optString("em"),
                            address = o.optString("ad"), birthday = o.optString("bd"),
                            notes = o.optString("nt"),
                            hasWhatsapp = o.optBoolean("wa", false), hasTelegram = o.optBoolean("tg", false),
                            createdAt = o.optLong("t", System.currentTimeMillis())
                        )
                    )
                    contactCount++
                }
            }

            // রিস্টোরের পর SIP অ্যাকাউন্টগুলো সাথে সাথে কানেক্ট (নতুন ইনস্টলে ম্যানুয়ালি আর কিছু করতে হয় না)
            if (accCount > 0) {
                try {
                    com.noxsip.app.sip.SipEngine.getInstance(context.applicationContext).registerAllSaved()
                    com.noxsip.app.service.SipForegroundService.start(context.applicationContext)
                } catch (_: Exception) {
                }
            }
            Summary(accCount, callCount, contactCount)
        }
}
