package com.ipdial.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Simple mock sync helper since Firebase is removed.
 */
class FirestorePointsSync(private val repo: AccountRepository) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val deviceName: String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    fun startListening() {
        // Disabled
    }

    fun incrementPoints(amount: Int) {
        scope.launch {
            val currentPoints = repo.proPoints.first()
            repo.setProPoints(currentPoints + amount)
        }
    }

    fun pushUpdate(points: Int, expiration: Long) {
        // Disabled
    }

    fun claimReferral(refCode: String, onComplete: (Boolean, String) -> Unit) {
        // Disabled without backend
        onComplete(false, "Referral system requires backend connection")
    }

    fun redeemPoints(cost: Int, newExpiration: Long) {
        scope.launch {
            val currentPoints = repo.proPoints.first()
            repo.setProPoints(currentPoints - cost)
            repo.setProExpiration(newExpiration)
        }
    }
}


