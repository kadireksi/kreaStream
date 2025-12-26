package com.kreastream

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.TimeUnit

// Improved downloader with better headers to match official NewPipe app
class NewPipeDownloader(builder: OkHttpClient.Builder): Downloader() {
    private val client: OkHttpClient = builder.readTimeout(30, TimeUnit.SECONDS).build()
    override fun execute(request: Request): Response {
        val httpMethod: String = request.httpMethod()
        val url: String = request.url()
        val headers: Map<String, List<String>> = YoutubeParsingHelper.getCookieHeader()
        val dataToSend: ByteArray? = request.dataToSend()
        var requestBody: RequestBody? = null
        if (dataToSend != null) {
            requestBody = dataToSend.toRequestBody(null, 0, dataToSend.size)
        }
        val requestBuilder: okhttp3.Request.Builder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody).url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Accept-Encoding", "gzip, deflate")
            .addHeader("Accept", "*/*")
            .addHeader("Sec-Fetch-Dest", "empty")
            .addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "same-origin")

        for ((headerName, headerValueList) in headers) {
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                for (headerValue in headerValueList) {
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }
        val response = client.newCall(requestBuilder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }
        val body = response.body
        val responseBodyToReturn: String = body.string()
        val latestUrl = response.request.url.toString()
        return Response(
            response.code, response.message, response.headers.toMultimap(),
            responseBodyToReturn, latestUrl
        )
    }

    companion object {
        // Updated User-Agent to match latest Chrome/Android
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-A515F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private var instance: NewPipeDownloader? = null

        fun init(builder: OkHttpClient.Builder?): NewPipeDownloader? {
            instance = NewPipeDownloader(
                builder ?: OkHttpClient.Builder()
            )
            return instance
        }

        fun getInstance(): NewPipeDownloader? {
            if (instance == null) {
                init(null)
            }
            return instance
        }
    }
}