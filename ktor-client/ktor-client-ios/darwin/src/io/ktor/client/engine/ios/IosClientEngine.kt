/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.ios

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import platform.Foundation.*
import platform.darwin.*
import kotlin.coroutines.*
import kotlin.reflect.*

internal class IosClientEngine(override val config: IosClientEngineConfig) : HttpClientEngineBase("ktor-ios") {
    // TODO: replace with UI dispatcher
    override val dispatcher = Dispatchers.Unconfined

    @UseExperimental(ExperimentalStdlibApi::class)
    override val supportedExtensions: Set<KType> = setOf(typeOf<HttpTimeout.Configuration>())

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()

        return suspendCancellableCoroutine { continuation ->
            val requestTime = GMTDate()

            val delegate = object : NSObject(), NSURLSessionDataDelegateProtocol {
                val chunks = Channel<ByteArray>(Channel.UNLIMITED)

                override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
                    val content = didReceiveData.toByteArray()
                    if (!chunks.offer(content)) throw IosHttpRequestException()
                }

                override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
                    chunks.close()

                    if (didCompleteWithError != null) {
                        val mappedException = when (didCompleteWithError.code) {
                            NSURLErrorTimedOut -> HttpSocketTimeoutException()
                            else -> IosHttpRequestException(didCompleteWithError)
                        }

                        continuation.resumeWithException(mappedException)
                        return
                    }

                    val rawResponse = task.response as NSHTTPURLResponse

                    @Suppress("UNCHECKED_CAST")
                    val headersDict = rawResponse.allHeaderFields as Map<String, String>

                    val status = HttpStatusCode.fromValue(rawResponse.statusCode.toInt())
                    val headers = buildHeaders {
                        headersDict.mapKeys { (key, value) -> append(key, value) }
                    }

                    val responseBody = writer(coroutineContext, autoFlush = true) {
                        while (!chunks.isClosedForReceive) {
                            val chunk = chunks.receive()
                            channel.writeFully(chunk)
                        }
                    }.channel

                    val version = HttpProtocolVersion.HTTP_1_1

                    val response = HttpResponseData(
                        status, requestTime, headers, version,
                        responseBody, callContext
                    )

                    continuation.resume(response)
                }

                override fun URLSession(
                    session: NSURLSession,
                    task: NSURLSessionTask,
                    willPerformHTTPRedirection: NSHTTPURLResponse,
                    newRequest: NSURLRequest,
                    completionHandler: (NSURLRequest?) -> Unit
                ) {
                    completionHandler(null)
                }
            }

            val configuration = NSURLSessionConfiguration.defaultSessionConfiguration()
            configuration.setupProxy(config)
            config.sessionConfig(configuration)

            val session = NSURLSession.sessionWithConfiguration(
                configuration,
                delegate, delegateQueue = NSOperationQueue.mainQueue()
            )

            val url = URLBuilder().takeFrom(data.url).buildString()
            val nativeRequest = NSMutableURLRequest.requestWithURL(NSURL(string = url))
            nativeRequest.setupSocketTimeout(data)

            mergeHeaders(data.headers, data.body) { key, value ->
                nativeRequest.setValue(value, key)
            }

            nativeRequest.setCachePolicy(NSURLRequestReloadIgnoringCacheData)
            nativeRequest.setHTTPMethod(data.method.value)

            launch(callContext) {
                val content = data.body
                val body = when (content) {
                    is OutgoingContent.ByteArrayContent -> content.bytes().toNSData()
                    is OutgoingContent.WriteChannelContent -> writer(dispatcher) {
                        content.writeTo(channel)
                    }.channel.readRemaining().readBytes().toNSData()
                    is OutgoingContent.ReadChannelContent -> content.readFrom().readRemaining().readBytes().toNSData()
                    is OutgoingContent.NoContent -> null
                    else -> throw UnsupportedContentTypeException(content)
                }

                body?.let { nativeRequest.setHTTPBody(it) }

                config.requestConfig(nativeRequest)
                val task = session.dataTaskWithRequest(nativeRequest)
                continuation.invokeOnCancellation { cause ->
                    if (cause != null && task.state == NSURLSessionTaskStateRunning) {
                        task.cancel()
                    }
                }
                task.resume()
            }
        }
    }
}

/**
 * Update [NSMutableURLRequest] and setup timeout interval that equal to socket interval specified by [HttpTimeout].
 */
private fun NSMutableURLRequest.setupSocketTimeout(requestData: HttpRequestData) {
    // iOS timeout works like a socket timeout.
    requestData.getExtension<HttpTimeout.Configuration>()?.socketTimeout?.let {
        // Timeout should be specified in seconds.
        setTimeoutInterval(it / 1000.0)
    }
}

