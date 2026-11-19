package com.noxsip.app.data.models

/**
 * SIP অ্যাকাউন্ট ডেটা মডেল
 * id: অভ্যন্তরীণ ইউনিক আইডি (খালি হলে সেভ করার সময় নতুন তৈরি হয়)
 * name: ব্যবহারকারীর দেওয়া অ্যাকাউন্টের নাম (বাধ্যতামূলক)
 */
data class SipAccountData(
    val id: String = "",
    val name: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val port: Int = 5060,
    val transport: String = "UDP",
    val proxy: String = "",
    // NAT: STUN/TURN সার্ভার (host:port) ও TURN লগইন; ফাঁকা থাকলে আগের মতো বন্ধ
    val stunServer: String = "",
    val turnUsername: String = "",
    val turnPassword: String = "",
    val displayName: String = "",
    val autoStart: Boolean = false,
    val enabled: Boolean = true // false = স্থায়ীভাবে বন্ধ (কানেক্ট হয় না)
)
