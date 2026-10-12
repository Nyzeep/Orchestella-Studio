package io.github.nyzeep.orchestella.ssh

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * File-based persistence store for SSH profiles in app-private storage.
 */
class SshProfileStore(context: Context) {

    private val storageFile = File(context.filesDir, "ssh_profiles.json")

    @Synchronized
    fun getAllProfiles(): List<SshProfile> {
        if (!storageFile.exists()) {
            return emptyList()
        }
        return try {
            val content = storageFile.readText(StandardCharsets.UTF_8)
            val array = JSONArray(content)
            val result = ArrayList<SshProfile>(array.length())
            for (i in 0 until array.length()) {
                result.add(SshProfile.fromJson(array.getJSONObject(i)))
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveProfiles(profiles: List<SshProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        storageFile.writeText(array.toString(2), StandardCharsets.UTF_8)
    }

    @Synchronized
    fun getProfileForInstance(instanceId: String): SshProfile? {
        return getAllProfiles().firstOrNull { it.instanceId == instanceId }
    }

    @Synchronized
    fun saveOrUpdate(profile: SshProfile) {
        val current = getAllProfiles().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            current[index] = profile
        } else {
            current.add(0, profile)
        }
        saveProfiles(current)
    }

    @Synchronized
    fun deleteProfile(id: String) {
        val filtered = getAllProfiles().filterNot { it.id == id }
        saveProfiles(filtered)
    }
}
