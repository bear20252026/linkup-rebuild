package com.youloft.linkup.net

import com.google.gson.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface LinkUpApi {

    @GET("api/relation/pre_confirm")
    suspend fun relationPreConfirm(@Query("code") code: Long): ApiResponse<JsonElement>

    @GET("api/setting/all_devices")
    suspend fun settingAllDevices(): ApiResponse<JsonElement>

    @GET("api/talk/enshrine_list")
    suspend fun talkEnshrineList(): ApiResponse<JsonElement>

    @POST("api/user/update/push/token")
    suspend fun userUpdatePushToken(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/phone/state/report")
    suspend fun phoneStateReport(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/version/check")
    suspend fun versionCheck(@Query("queryId") queryId: String): ApiResponse<JsonElement>

    @GET("api/sensitive/server_timer")
    suspend fun sensitiveServerTimer(@Query("eventTime") eventTime: Long): ApiResponse<JsonElement>

    @POST("api/user/show/report")
    suspend fun userShowReport(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/feedback")
    suspend fun feedback(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/anniversary/choose")
    suspend fun anniversaryChoose(@Query("userId") userId: Long, @Query("friendId") friendId: Long, @Query("friendAvatar") friendAvatar: String): ApiResponse<JsonElement>

    @GET("api/four_quadrant_list/get_list")
    suspend fun fourQuadrantListGetList(@Query("listType") listType: Int): ApiResponse<JsonElement>

    @POST("api/relation/lock")
    suspend fun relationLock(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("/api/feedback/create")
    suspend fun apiFeedbackCreate(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/relation/confirm")
    suspend fun relationConfirm(@Query("code") code: Long, @Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/user/report/getui_cid")
    suspend fun userReportGetuiCid(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/setting/update")
    suspend fun settingUpdate(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/relation/lock/cover/del")
    suspend fun relationLockCoverDel(): ApiResponse<JsonElement>

    @GET("api/sensitive/user")
    suspend fun sensitiveUser(@Query("date") date: String, @Query("mainUser") mainUser: Long): ApiResponse<JsonElement>

    @GET("api/setting/user_list_v2")
    suspend fun settingUserListV2(@Query("pageSize") pageSize: Int): ApiResponse<JsonElement>

    @POST("api/anniversary/createOrEdit")
    suspend fun anniversaryCreateoredit(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/client/config")
    suspend fun clientConfig(): ApiResponse<JsonElement>

    @POST("api/index/realtime/voice/delete")
    suspend fun indexRealtimeVoiceDelete(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/location/batch/report")
    suspend fun locationBatchReport(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/pop/homePage")
    suspend fun popHomepage(): ApiResponse<JsonElement>

    @POST("api/talk/enshrine_delete")
    suspend fun talkEnshrineDelete(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/sensitive/user/days")
    suspend fun sensitiveUserDays(): ApiResponse<JsonElement>

    @GET("api/feedback/checkPop")
    suspend fun feedbackCheckpop(@Query("userId") userId: Long): ApiResponse<JsonElement>

    @GET("api/relation/lock_list")
    suspend fun relationLockList(): ApiResponse<JsonElement>

    @GET("api/widget/track")
    suspend fun widgetTrack(): ApiResponse<JsonElement>

    @GET("api/location/days")
    suspend fun locationDays(@Query("queryId") queryId: Long): ApiResponse<JsonElement>

    @POST("api/sms/click_to_login")
    suspend fun smsClickToLogin(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/risk/real_name_auth")
    suspend fun riskRealNameAuth(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/relation/update_remark")
    suspend fun relationUpdateRemark(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/address/tag_list")
    suspend fun addressTagList(): ApiResponse<JsonElement>

    @GET("/api/pay/check/order_status")
    suspend fun apiPayCheckOrderStatus(@Query("orderId") orderId: String): ApiResponse<JsonElement>

    @GET("api/volcano/token")
    suspend fun volcanoToken(): ApiResponse<JsonElement>

    @GET("api/relation/info")
    suspend fun relationInfo(@Query("queryId") queryId: Long): ApiResponse<JsonElement>

    @POST("api/user/logout")
    suspend fun userLogout(): ApiResponse<JsonElement>

    @GET("api/setting/friend_sens")
    suspend fun settingFriendSens(@Query("friendId") friendId: String): ApiResponse<JsonElement>

    @GET("api/talk/init")
    suspend fun talkInit(): ApiResponse<JsonElement>

    @GET("api/tool/rsa/public_key")
    suspend fun toolRsaPublicKey(): ApiResponse<JsonElement>

    @POST("api/phone/app_use/report")
    suspend fun phoneAppUseReport(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/user/login")
    suspend fun userLogin(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/phone/app_use/v2/report")
    suspend fun phoneAppUseV2Report(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/relation/unlock")
    suspend fun relationUnlock(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/phone/sys_screen/report")
    suspend fun phoneSysScreenReport(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/index")
    suspend fun index(): ApiResponse<JsonElement>

    @GET("api/user/default_avatar")
    suspend fun userDefaultAvatar(): ApiResponse<JsonElement>

    @GET("api/talk/enshrine")
    suspend fun talkEnshrine(@Query("image") image: String, @Query("userId") userId: Long, @Query("hash") hash: String): ApiResponse<JsonElement>

    @GET("api/setting/user_list")
    suspend fun settingUserList(@Query("pageSize") pageSize: Int): ApiResponse<JsonElement>

    @POST("/api/attribution/choice_channel")
    suspend fun apiAttributionChoiceChannel(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("/api/pay/available_types")
    suspend fun apiPayAvailableTypes(): ApiResponse<JsonElement>

    @GET("api/vip/index")
    suspend fun vipIndex(): ApiResponse<JsonElement>

    @POST("api/index/realtime/voice/send")
    suspend fun indexRealtimeVoiceSend(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/pay/transaction")
    suspend fun payTransaction(@Query("productId") productId: String, @Query("fromScene") fromScene: Int, @Query("payType") payType: Int, @Query("gid") gid: Int, @Query("giftUserId") giftUserId: Long): ApiResponse<JsonElement>

    @POST("api/user/change_bind")
    suspend fun userChangeBind(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/talk/gifs")
    suspend fun talkGifs(@Query("page") page: Int, @Query("pageSize") pageSize: Int): ApiResponse<JsonElement>

    @POST("api/location/report")
    suspend fun locationReport(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/risk/report")
    suspend fun riskReport(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/talk/phone_state")
    suspend fun talkPhoneState(@Query("mainUser") mainUser: Long): ApiResponse<JsonElement>

    @POST("/api/four_quadrant_list/del")
    suspend fun apiFourQuadrantListDel(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("/api/relation/apply")
    suspend fun apiRelationApply(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("/api/vip/share_to_friend")
    suspend fun apiVipShareToFriend(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/four_quadrant_list/edit")
    suspend fun fourQuadrantListEdit(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/attribution/activation")
    suspend fun attributionActivation(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/screenshot/done")
    suspend fun screenshotDone(@Query("friendId") friendId: Long, @Query("SocializeConstants.KEY_PIC") socializeconstantsKeyPic: String): ApiResponse<JsonElement>

    @POST("api/address/tag")
    suspend fun addressTag(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/user/info")
    suspend fun userInfo(): ApiResponse<JsonElement>

    @GET("api/widget/location")
    suspend fun widgetLocation(): ApiResponse<JsonElement>

    @POST("api/setting/update")
    suspend fun settingUpdate2(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("/api/relation/apply_list")
    suspend fun apiRelationApplyList(@Query("page") page: Int, @Query("pageSize") pageSize: Int): ApiResponse<JsonElement>

    @POST("api/talk/enshrine_top")
    suspend fun talkEnshrineTop(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/feedback/check")
    suspend fun feedbackCheck(@Query("userId") userId: Long, @Query("deviceId") deviceId: String): ApiResponse<JsonElement>

    @GET("api/relation/lock/question_list")
    suspend fun relationLockQuestionList(): ApiResponse<JsonElement>

    @GET("api/ad_config/list")
    suspend fun adConfigList(): ApiResponse<JsonElement>

    @GET("api/setting/im")
    suspend fun settingIm(): ApiResponse<JsonElement>

    @POST("api/user/android/install_app")
    suspend fun userAndroidInstallApp(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/weather/baiduWeather")
    suspend fun weatherBaiduweather(@Query("data_type") dataType: String, @Query("district_id") districtId: String, @Query("force") force: Int): ApiResponse<JsonElement>

    @POST("api/user/update/info")
    suspend fun userUpdateInfo(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/relation/lock/cover/list")
    suspend fun relationLockCoverList(): ApiResponse<JsonElement>

    @GET("api/screenshot/list")
    suspend fun screenshotList(): ApiResponse<JsonElement>

    @GET("api/sms/login/cut")
    suspend fun smsLoginCut(): ApiResponse<JsonElement>

    @GET("api/index/realtime/voice/friend/list")
    suspend fun indexRealtimeVoiceFriendList(): ApiResponse<JsonElement>

    @GET("api/user/cut/list")
    suspend fun userCutList(): ApiResponse<JsonElement>

    @GET("api/location/v2/tracks")
    suspend fun locationV2Tracks(@Query("queryId") queryId: Long, @Query("date") date: String): ApiResponse<JsonElement>

    @POST("api/relation/del")
    suspend fun relationDel(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/relation/list")
    suspend fun relationList(@Query("page") page: Int, @Query("pageSize") pageSize: Int, @Query("key") key: String): ApiResponse<JsonElement>

    @POST("api/phone/call_record/report")
    suspend fun phoneCallRecordReport(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/sms/send")
    suspend fun smsSend(@Query("phone") phone: String, @Query("region") region: String): ApiResponse<JsonElement>

    @POST("api/relation/update")
    suspend fun relationUpdate(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/tool/uploadLog")
    suspend fun toolUploadlog(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/relation/main_user")
    suspend fun relationMainUser(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @GET("api/index/realtime/voice/list")
    suspend fun indexRealtimeVoiceList(): ApiResponse<JsonElement>

    @POST("/api/relation/match_by_phone")
    suspend fun apiRelationMatchByPhone(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/sensitive/report")
    suspend fun sensitiveReport(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>

    @POST("api/user/unregister")
    suspend fun userUnregister(): ApiResponse<JsonElement>

    @POST("api/index/realtime/voice/upload")
    suspend fun indexRealtimeVoiceUpload(@Body payload: Map<String, Any?>): ApiResponse<JsonElement>
}
