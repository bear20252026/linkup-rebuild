package com.youloft.watcher.data

import com.youloft.linkup.api.dto.TrackRecordBean
import com.youloft.linkup.net.ApiResponse
import com.youloft.linkup.net.LinkUpApi

interface TrackRepository {
    suspend fun days(userId: Long): ApiResponse<List<String>>
    suspend fun record(userId: Long, date: String): ApiResponse<TrackRecordBean>
}

class NetworkTrackRepository(private val api: LinkUpApi) : TrackRepository {
    override suspend fun days(userId: Long): ApiResponse<List<String>> = api.locationDays(userId)
    override suspend fun record(userId: Long, date: String): ApiResponse<TrackRecordBean> =
        api.locationV2Tracks(userId, date)
}
