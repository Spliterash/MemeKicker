package ru.spliterash.memeKicker.vkApiFix

import com.vk.api.sdk.client.VkApiClient
import com.vk.api.sdk.client.actors.GroupActor
import com.vk.api.sdk.client.actors.UserActor
import com.vk.api.sdk.exceptions.ApiException
import com.vk.api.sdk.exceptions.ClientException
import com.vk.api.sdk.objects.callback.longpoll.responses.GetLongPollEventsResponse
import com.vk.api.sdk.objects.groups.LongPollServer
import com.vk.api.sdk.objects.groups.responses.GetLongPollServerResponse
import org.apache.http.ConnectionClosedException
import org.slf4j.LoggerFactory
import kotlin.concurrent.Volatile

abstract class FixedLongPollApi protected constructor(private val client: VkApiClient, private val waitTime: Int) :
    FixedEventsHandler() {
    @Volatile
    var isRunning = false
        private set

    private fun initServer(lpServerResponse: GetLongPollServerResponse): LongPollServer {
        return LongPollServer()
            .setKey(lpServerResponse.key)
            .setTs(lpServerResponse.ts)
            .setServer(lpServerResponse.server)
    }

    private fun getLongPollServer(actor: UserActor, groupId: Int): LongPollServer? {
        return try {
            initServer(client.groupsLongPoll().getLongPollServer(actor, groupId).execute())
        } catch (e: ApiException) {
            null
        } catch (e: ClientException) {
            null
        }
    }

    private fun getLongPollServer(actor: GroupActor): LongPollServer? {
        return try {
            initServer(client.groupsLongPoll().getLongPollServer(actor, actor.groupId).execute())
        } catch (e: ApiException) {
            null
        } catch (e: ClientException) {
            null
        }
    }

    @Throws(ConnectionClosedException::class)
    private fun handleUpdates(lpServer: LongPollServer) {
        isRunning = true
        try {
            LOG.info("LongPoll handler started to handle events")
            var eventsResponse: GetLongPollEventsResponse
            var timestamp = lpServer.ts
            while (isRunning) {
                eventsResponse = client.longPoll()
                    .getEvents(lpServer.server, lpServer.key, timestamp)
                    .waitTime(waitTime)
                    .execute()
                eventsResponse.updates.forEach {
                    parse(
                        gson.fromJson(
                            it,
                            FixedCallbackMessage::class.java
                        )
                    )
                }
                timestamp = eventsResponse.ts
            }
            LOG.info("LongPoll handler stopped to handle events")
        } catch (e: ApiException) {
            /*
            Actually instead of GetLongPollEventsResponse there might be returned error like:
            {"failed":1,"ts":30} or {"failed":2}, but it directly handled in execute() method.
            There are 2 ways: deserialize manually response from string OR do reconnection in each
            error case. There is second way - keep use typed object and reconnect when any error.
            */
            LOG.error("Getting LongPoll events was failed", e)
            throw ConnectionClosedException()
        } catch (e: ClientException) {
            LOG.error("Getting LongPoll events was failed", e)
            throw ConnectionClosedException()
        }
        isRunning = false
    }

    // Blocking shit
    fun run(actor: GroupActor) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val lpServer = getLongPollServer(actor)
                if (lpServer == null) {
                    LOG.warn("Failed to get lp server, waiting 10 second")
                    Thread.sleep(10000)
                    continue
                }
                handleUpdates(lpServer)
            } catch (ignored: ConnectionClosedException) {
                // IGNORE
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(FixedLongPollApi::class.java)
    }
}
