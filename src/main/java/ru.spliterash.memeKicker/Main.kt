package ru.spliterash.memeKicker

import com.google.gson.Gson
import com.vk.api.sdk.client.VkApiClient
import com.vk.api.sdk.client.actors.GroupActor
import com.vk.api.sdk.httpclient.HttpTransportClient
import com.vk.api.sdk.objects.callback.GroupJoin
import com.vk.api.sdk.objects.callback.GroupLeave
import com.vk.api.sdk.objects.callback.MessageObject
import com.vk.api.sdk.objects.groups.responses.IsMemberResponse
import ru.spliterash.memeKicker.vkApiFix.FixedLongPollApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object Main {
    private const val PEERS_FILE = "peers.txt"

    val members = hashSetOf<Int>()
    lateinit var client: VkApiClient
    lateinit var actor: GroupActor
    lateinit var noticedPeers: HashSet<Int>

    @JvmStatic
    fun main(args: Array<String>) {
        val config = Gson().fromJson(
            FileInputStream("config.json")
                .readAllBytes()
                .decodeToString(),
            Config::class.java
        )
        val file = File(PEERS_FILE)
        if (!file.isFile)
            file.createNewFile()
        noticedPeers = FileInputStream(PEERS_FILE)
            .readAllBytes()
            .decodeToString()
            .split("\n")
            .filter { it.isNotBlank() }
            .map { it.trim().toInt() }
            .toHashSet()

        actor = GroupActor(config.groupId, config.token)
        client = VkApiClient(HttpTransportClient.getInstance())
        Handler().run(actor)
    }

    private fun rememberPeer(peer: Int) {
        if (!noticedPeers.add(peer)) return

        val toAdd = "$peer\n".encodeToByteArray()
        FileOutputStream(PEERS_FILE, true)
            .apply {
                write(toAdd)
                close()
            }
    }

    class Handler : FixedLongPollApi(client, 25) {
        override fun messageNew(groupId: Int, messageObject: MessageObject) {
            val message = messageObject.message

            if (message.peerId < 2000000000) return
            rememberPeer(message.peerId)
            val fromId = message.fromId
            if (fromId < 0) return
            if (members.contains(fromId)) return

            val response = client
                .groups()
                .isMember(actor, actor.groupId.toString())
                .userId(fromId)
                .execute()

            when (response) {
                IsMemberResponse.NO -> kick(message.peerId, fromId)
                IsMemberResponse.YES -> members += fromId
                null -> Unit
            }
        }


        override fun groupLeave(groupId: Int, message: GroupLeave) {
            members -= message.userId
            for (peerId in noticedPeers) {
                kick(peerId, message.userId)
            }
        }

        override fun groupJoin(groupId: Int, message: GroupJoin) {
            members += message.userId
        }
    }

    fun kick(peerId: Int, userToKick: Int) {
        try {
            client
                .messages()
                .removeChatUser(actor, peerId - 2000000000)
                .memberId(userToKick)
                .execute()
        } catch (ex: Exception) {
            // PASS
        }
    }
}