package com.youloft.linkup.api.dto

import com.google.gson.JsonElement

/** recovered from com/youloft/watcher/bean/ActivationResult.java */
data class ActivationResult(
    val adChannel: Int? = null,
)

/** recovered from com/youloft/watcher/bean/AdConfig.java */
data class AdConfig(
    val adId: String? = null,
    val adType: Int = 0,
    val coldStartCount: Int = 0,
    val hotStartInterval: Int = 0,
    val hotStartTiming: Int = 0,
    val interstitialInterval: Long = 0L,
    val isVip: Int = 0,
    val noMatchShow: Int = 0,
    val preloadTime: Int = 0,
    val priority: Int = 0,
    val showRule: Int = 0,
    val splashLoadingTime: Int = 0,
)

/** recovered from com/youloft/watcher/bean/AdConfigRes.java */
data class AdConfigRes(
    val list: List<AdConfig?>? = null,
)

/** recovered from com/youloft/common/bean/Address.java */
data class Address(
    val city: String? = null,
    val district: String? = null,
    val poiName: String? = null,
    val province: String? = null,
    val street: String? = null,
    val streetNumber: String? = null,
)

/** recovered from com/youloft/watcher/bean/Anniversary.java */
data class Anniversary(
    val anniversaryTime: Long? = null,
    val avatar: String? = null,
    val f63402id: Int? = null,
    val title: String? = null,
    val userId: Long? = null,
)

/** recovered from com/youloft/watcher/bean/AppUsage.java */
data class AppUsage(
    val appName: String? = null,
    val count: Int = 0,
    val duration: Long = 0L,
    val icon: String? = null,
    val isSelected: JsonElement? = null,
    val lastUseTime: Long = 0L,
    val packageName: String? = null,
    val statisticsType: Int = 0,
)

/** recovered from com/youloft/watcher/bean/AppUseCard.java */
data class AppUseCard(
    val usageStatistics: List<AppUsage?>? = null,
    val useToday: Int = 0,
    val usedYesterday: Int = 0,
)

/** recovered from com/youloft/watcher/bean/AutoReport.java */
data class AutoReport(
    val friendsImSetting: List<FriendsImSetting?>? = null,
    val imSetting: AutoReportSettings? = null,
)

/** recovered from com/youloft/watcher/bean/AutoReportSettings.java */
data class AutoReportSettings(
    val imAccountNotice: Boolean = false,
    val imAppUseNotice: Boolean = false,
    val imAppUseNoticeType: Int = 0,
    val imAppUseStatisticNotice: Boolean = false,
    val imArrivalLeaveNotice: Boolean = false,
    val imArrivalNotice: Boolean = false,
    val imBatteryStateNotice: Boolean = false,
    val imCallRecordNoticeType: Int = 0,
    val imFourQuadrantsCompleteNotice: Boolean = false,
    val imFourQuadrantsCreateNotice: Boolean = false,
    val imFourQuadrantsNoticeType: Int = 0,
    val imImComingNotice: Boolean = false,
    val imLinkUpNoticeType: Int = 0,
    val imLocationNoticeType: Int = 0,
    val imLocationPermissionNotice: Boolean = false,
    val imNetworkNotice: Boolean = false,
    val imOutgoingNotice: Boolean = false,
    val imPhoneNoticeType: Int = 0,
    val imPhoneStateNoticeType: Int = 0,
    val imPhoneStatisticNotice: Boolean = false,
    val imPhoneUseNotice: Boolean = false,
    val imSensSettingNotice: Boolean = false,
    val imSensitiveNotice: Boolean = false,
    val imStayTimeNotice: Boolean = false,
    val imUseLinkupNotice: Boolean = false,
)

/** recovered from com/youloft/watcher/bean/Avatar.java */
data class Avatar(
    val avatar: String? = null,
    val f63403id: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/AvatarList.java */
data class AvatarList(
    val list: List<Avatar?>? = null,
)

/** recovered from com/youloft/watcher/bean/Birthday.java */
data class Birthday(
    val date: String? = null,
    val isLunar: Boolean = false,
)

/** recovered from com/youloft/watcher/bean/CallRecord.java */
data class CallRecord(
    val contact: String? = null,
    val duration: Long = 0L,
    val isIncoming: Boolean = false,
    val phone: String? = null,
    val time: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/ChannelConfig.java */
data class ChannelConfig(
    val autoReportAddressShow: Int = 0,
    val halfScreenVipPageShow: Boolean = false,
    val imGroupHonor: String? = null,
    val imGroupHuawei: String? = null,
    val imGroupOppo: String? = null,
    val imGroupVivo: String? = null,
    val imGroupXiaomi: String? = null,
    val notRelativesLockShow: Boolean = false,
    val vipMarketingCopy: String? = null,
)

/** recovered from com/youloft/watcher/bean/ChatCollectionData.java */
data class ChatCollectionData(
    val talk_enshrine_list: List<ChatEmojiCollectBean?>? = null,
)

/** recovered from com/youloft/watcher/bean/ChatEmojiCollectBean.java */
data class ChatEmojiCollectBean(
    val hasAlpha: Boolean = false,
    val height: Int = 0,
    val f63405id: Int = 0,
    val image: String? = null,
    val image_thumb: String? = null,
    val res: Int? = null,
    val selected: Boolean = false,
    val width: Int = 0,
)

/** recovered from com/youloft/watcher/bean/im/ChatGifBean.java */
data class ChatGifBean(
    val hasAlpha: Boolean = false,
    val height: Int = 0,
    val f63414id: Int = 0,
    val image_url: String? = null,
    val width: Int = 0,
)

/** recovered from com/youloft/watcher/bean/im/ChatGifData.java */
data class ChatGifData(
    val gif_list: List<ChatGifBean?>? = null,
)

/** recovered from com/youloft/watcher/bean/friend/ConfirmAdd.java */
data class ConfirmAdd(
    val friendLimit: Int = 0,
    val friendUserId: Long? = null,
)

/** recovered from com/youloft/watcher/bean/login/Cut.java */
data class Cut(
    val loginType: String? = null,
)

/** recovered from com/youloft/watcher/bean/CutConfig.java */
data class CutConfig(
    val extra: JsonElement? = null,
    val result: String? = null,
    val ruleId: Double = 0.0,
    val `type`: Int = 0,
)

/** recovered from com/youloft/watcher/bean/CutConfigBean.java */
data class CutConfigBean(
    val list: List<CutConfig?>? = null,
)

/** recovered from com/youloft/watcher/bean/EvaluateRes.java */
data class EvaluateRes(
    val isPop: Boolean = false,
    val typeIds: List<Int?>? = null,
)

/** recovered from com/youloft/watcher/bean/FeedbackPopBean.java */
data class FeedbackPopBean(
    val feedbackPop: Boolean = false,
)

/** recovered from com/youloft/watcher/bean/friend/Friend.java */
data class Friend(
    val avatar: String? = null,
    val isFriendLimit: Boolean = false,
    val nickname: String? = null,
    val otherFriendLimit: Boolean = false,
    val otherRiskLevel: Int? = null,
    val permission: Permission? = null,
    val riskLevel: Int? = null,
    val sex: String? = null,
    val sslCertificateError: Boolean = false,
    val userId: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/friend/FriendApply.java */
data class FriendApply(
    val applyStatus: Int = 0,
    val avatar: String? = null,
    val friendId: Long = 0L,
    val nickname: String? = null,
    val sex: String? = null,
    val userId: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/friend/FriendInfo.java */
data class FriendInfo(
    val avatar: String? = null,
    val birthday: Birthday? = null,
    val client: String? = null,
    val createAt: Long = 0L,
    val nickname: String? = null,
    val otherFriendLimit: Boolean = false,
    val permission: Permission? = null,
    val relation: String? = null,
    val remark: String? = null,
    val sex: String? = null,
    val userId: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/FriendList.java */
data class FriendList(
    val list: List<JsonElement?>? = null,
    val av: String? = null,
    val avatar: String? = null,
    val createAt: Long = 0L,
    val deleteAt: Long = 0L,
    val f63406id: Long = 0L,
    val imSuspend: Boolean = false,
    val location: Location? = null,
    val newFriend: Boolean = false,
    val nickname: String? = null,
    val permissionValue: Boolean = false,
    val receivedFirstMsg: Boolean = false,
    val relation: Int = 0,
    val remark: String? = null,
    val remarkToMe: String? = null,
    val riskLevel: Int = 0,
    val riskTypes: List<Int?>? = null,
    val selected: Boolean = false,
    val sendFirstMsg: Boolean = false,
    val sex: Int = 0,
    val unregisterTime: Long = 0L,
    val userId: Long = 0L,
    val vipExpireTime: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/FriendLocation.java */
data class FriendLocation(
    val address: Address? = null,
    val arrivalTime: Long = 0L,
    val endMeetingTime: Long = 0L,
    val lastMeetingTime: Long = 0L,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val pairTime: Long = 0L,
    val speed: Float = 0f,
    val `type`: Int = 0,
)

/** recovered from com/youloft/watcher/bean/friend/FriendRequest.java */
data class FriendRequest(
    val avatar: String? = null,
    val birthday: Birthday? = null,
    val createAt: Long = 0L,
    val isFriendLimit: Boolean = false,
    val isUnregister: Boolean = false,
    val nickname: String? = null,
    val relation: Int = 0,
    val riskLevel: Int? = null,
    val riskTypes: List<Int?>? = null,
    val sex: Int = 0,
    val status: Int = 0,
    val userId: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/friend/FriendRequestList.java */
data class FriendRequestList(
    val isFriendLimit: Boolean? = null,
    val lastApplyUser: LastApplyUser? = null,
    val list: List<FriendRequest?>? = null,
    val unreadCount: Int? = null,
)

/** recovered from com/youloft/watcher/bean/FriendState.java */
data class FriendState(
    val currentBattery: Int = 0,
    val isCharging: Boolean = false,
    val lastOfflineTime: Long = 0L,
    val lastRecoverTime: Long = 0L,
    val lockTime: Int = 0,
    val networkType: Int = 0,
    val offlineTimeConf: Int = 0,
    val offlineTipTimeConf: Int = 0,
    val screenTime: Long = 0L,
    val screenType: Int = 0,
    val serverTime: Long = 0L,
    val wifi: String? = null,
)

/** recovered from com/youloft/watcher/bean/FriendsImSetting.java */
data class FriendsImSetting(
    val imSetting: AutoReportSettings? = null,
    val userId: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/HomeData.java */
data class HomeData(
    val appUseCard: AppUseCard? = null,
    val callRecordList: List<CallRecord?>? = null,
    val friendLocation: FriendLocation? = null,
    val friendPermission: PermissionUpdateBean? = null,
    val friendState: FriendState? = null,
    val phoneInfoCard: PhoneStateInfo? = null,
    val phoneUseCard: PhoneUseCard? = null,
    val relationInfo: RelationInfo? = null,
    val selfPermission: PermissionUpdateBean? = null,
    val sensitiveCard: List<SensBean?>? = null,
    val trackCard: TrackCard? = null,
    val trackCardForStay: TrackCardForStay? = null,
)

/** recovered from com/youloft/watcher/bean/friend/LastApplyUser.java */
data class LastApplyUser(
    val avatar: String? = null,
    val nickname: String? = null,
    val sex: Int = 0,
    val userId: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/ListBean.java */
data class ListBean<T>(
    val list: List<T?>? = null,
    val sendLimit: Int = 0,
)

/** recovered from com/youloft/watcher/bean/Location.java */
data class Location(
    val accuracy: Float = 0f,
    val address: Address? = null,
    val f63408id: Long = 0L,
    val imPush: JsonElement? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val locType: Int = 0,
    val speed: Float = 0f,
    val timePeriod: Long = 0L,
    val `type`: Int = 0,
    val isNewLocation: Boolean = false,
    val residenceTime: Long = 0L,
    val stayCount: Int = 0,
)

/** recovered from com/youloft/watcher/bean/LocationWidget.java */
data class LocationWidget(
    val friendLocation: FriendLocation? = null,
    val friendState: FriendState? = null,
    val sensLocation: Boolean = false,
    val serverTime: Long = 0L,
    val sysLocation: Boolean = false,
)

/** recovered from com/youloft/watcher/bean/vip/OrderStatus.java */
data class OrderStatus(
    val completed: Boolean = false,
)

/** recovered from com/youloft/watcher/bean/vip/Pay.java */
data class Pay(
    val WNLOrderId: String? = null,
    val alipay: String? = null,
    val deepLink: String? = null,
    val orderId: String? = null,
    val wechat: WxPay? = null,
)

/** recovered from com/youloft/common/bean/PayTypeItem.java */
data class PayTypeItem(
    val name: String? = null,
    val `type`: Int = 0,
)

/** recovered from com/youloft/common/bean/PayTypes.java */
data class PayTypes(
    val payTypes: List<PayTypeItem?>? = null,
)

/** recovered from com/youloft/watcher/bean/friend/Permission.java */
data class Permission(
    val sensAppUse: Boolean = false,
    val sensCall: Boolean = false,
    val sensLocation: Boolean = false,
    val sensLockPhone: Boolean = false,
    val sensPhoneState: Boolean = false,
    val sensPhoneUse: Boolean = false,
    val sensRealtimeVoice: Boolean = false,
    val sensScreenShot: Boolean = false,
    val sensSensitive: Boolean = false,
    val sensTrack: Boolean = false,
)

/** recovered from com/youloft/watcher/bean/PermissionUpdateBean.java */
data class PermissionUpdateBean(
    val androidLockApp: List<AppUsage?>? = null,
    val appleBindApp: List<AppUsage?>? = null,
    val friendIds: List<Long?>? = null,
    val otherShortcut: Boolean? = null,
    val selfShortcut: Boolean? = null,
    val sensAppUse: Boolean? = null,
    val sensCall: Boolean? = null,
    val sensLocation: Boolean? = null,
    val sensLockPhone: Boolean? = null,
    val sensPhoneState: Boolean? = null,
    val sensPhoneUse: Boolean? = null,
    val sensRealtimeVoice: Boolean? = null,
    val sensScreenShot: Boolean? = null,
    val sensSensitive: Boolean? = null,
    val sensTrack: Boolean? = null,
    val sysAppUse: Boolean? = null,
    val sysBarrierFree: Boolean? = null,
    val sysCall: Boolean? = null,
    val sysCallLog: Boolean? = null,
    val sysFloatWindow: Boolean? = null,
    val sysIAppUse: Int? = null,
    val sysLocation: Boolean? = null,
    val sysLocationFront: Boolean? = null,
    val sysMotion: Boolean? = null,
    val sysPush: Boolean? = null,
    val talkMsgDetail: Boolean? = null,
)

/** recovered from com/youloft/watcher/bean/PhoneStateInfo.java */
data class PhoneStateInfo(
    val availableTime: Int = 0,
    val battery: Int? = null,
    val batteryTemperature: Float = 0f,
    val bluetooth: Int = 0,
    val bootTime: Long = 0L,
    val brand: String? = null,
    val brightness: Int = 0,
    val cpuCores: Int = 0,
    val cpuName: String? = null,
    val currentBattery: Int = 0,
    val ip: String? = null,
    val isCharging: Boolean = false,
    val isLowPowerMode: Int = 0,
    val model: String? = null,
    val netSpeed: Float = 0f,
    val networkType: Int = 0,
    val osVersion: String? = null,
    val resolution: String? = null,
    val storage: Double = 0.0,
    val storageUsed: Double = 0.0,
    val trafficUsed: Double = 0.0,
    val volume: Int = 0,
    val wifi: String? = null,
    val wifiTrafficUsed: Double = 0.0,
)

/** recovered from com/youloft/watcher/bean/PhoneUseCard.java */
data class PhoneUseCard(
    val firstTime: Long = 0L,
    val maxContinuousTime: Long = 0L,
    val totalNum: Int = 0,
    val totalTime: Long = 0L,
)

/** recovered from com/youloft/common/bean/Pop.java */
data class Pop(
    val av: String? = null,
    val beginTime: Long = 0L,
    val buttonText: String? = null,
    val createTime: Long = 0L,
    val cutPopType: Int = 0,
    val description: String? = null,
    val endTime: Long = 0L,
    val extra: JsonElement? = null,
    val f63211id: Int = 0,
    val image: String? = null,
    val popType: Int = 0,
    val target: String? = null,
    val targetType: String? = null,
    val tips: String? = null,
    val title: String? = null,
    val typeId: Int = 0,
    val closeButton: Boolean = false,
    val currentPrice: Int = 0,
    val discountPrice: Int = 0,
    val entranceScenario: Int = 0,
    val friendAvatar: String? = null,
    val friendId: Long = 0L,
    val goodsId: Int = 0,
    val originPrice: Int = 0,
    val packetPrice: Int = 0,
    val payTypes: List<PayTypeItem?>? = null,
    val price: Int = 0,
    val productId: String? = null,
    val shareNum: Int = 0,
    val showButton: Boolean = false,
    val strategy: String? = null,
    val upgradePrice: Int = 0,
    val vipSubType: Int = 0,
)

/** recovered from com/youloft/common/bean/PopListBean.java */
data class PopListBean(
    val delayRefresh: Long = 0L,
    val popList: List<Pop?>? = null,
)

/** recovered from com/youloft/watcher/bean/privacy/PrivacyList.java */
data class PrivacyList(
    val sensSettings: List<SensSettings?>? = null,
    val user: User? = null,
)

/** recovered from com/youloft/watcher/bean/privacy/PrivacyRes.java */
data class PrivacyRes(
    val list: List<PrivacyList?>? = null,
)

/** recovered from com/youloft/watcher/bean/privacy/PrivacySetting.java */
data class PrivacySetting(
    val key: String? = null,
    val userList: List<JsonElement?>? = null,
    val avatar: String? = null,
    val nickname: String? = null,
    val sex: Int = 0,
    val userId: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/privacy/PrivacySettingList.java */
data class PrivacySettingList(
    val expand: Boolean = false,
    val key: String? = null,
    val list: List<PrivacySetting?>? = null,
    val userList: List<JsonElement?>? = null,
)

/** recovered from com/youloft/watcher/bean/vip/Product.java */
data class Product(
    val add: Int = 0,
    val client: Int = 0,
    val describe: String? = null,
    val f63419id: Int = 0,
    val originPrice: Double = 0.0,
    val packetPrice: Double = 0.0,
    val price: Double = 0.0,
    val productId: String? = null,
    val productName: String? = null,
    val productType: Int = 0,
    val shareNum: Int = 0,
    val status: Int = 0,
    val subscribeType: Int = 0,
    val vipSubType: Int = 0,
    val vipType: Int = 0,
)

/** recovered from com/youloft/watcher/bean/friend/PublicKey.java */
data class PublicKey(
    val publicKey: String? = null,
)

/** recovered from com/youloft/watcher/bean/RealtimeVoiceFriendBean.java */
data class RealtimeVoiceFriendBean(
    val av: String? = null,
    val avatar: String? = null,
    val client: String? = null,
    val nickname: String? = null,
    val remainTimes: Int = 0,
    val remark: String? = null,
    val sensRealtimeVoice: Boolean = false,
    val sex: Int = 0,
    val userId: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/RelationInfo.java */
data class RelationInfo(
    val av: String? = null,
    val avatar: String? = null,
    val client: String? = null,
    val nickname: String? = null,
    val osVersion: String? = null,
    val relation: Int = 0,
    val sex: Int = 0,
)

/** recovered from com/youloft/watcher/bean/im/RiskConfig.java */
data class RiskConfig(
    val riskBlackLinks: List<String?>? = null,
    val riskDataConfig: RiskDataConfig? = null,
    val riskIpList: List<String?>? = null,
    val riskWhiteLinks: List<String?>? = null,
    val riskWords: List<String?>? = null,
)

/** recovered from com/youloft/watcher/bean/im/RiskDataConfig.java */
data class RiskDataConfig(
    val riskFriendCount: Int = 0,
    val riskFriendCountCheck: Boolean = false,
    val riskIpCheck: Boolean = false,
    val riskLinkCheck: Boolean = false,
    val riskLinkCount: Int = 0,
    val riskNicknameCheck: Boolean = false,
    val riskWordsCheck: Boolean = false,
    val riskWordsCount: Int = 0,
    val useVpnCheck: Boolean = false,
)

/** recovered from com/youloft/watcher/bean/friend/SearchFriend.java */
data class SearchFriend(
    val isFriendLimit: Boolean = false,
    val list: List<FriendInfo?>? = null,
    val sslCertificateError: Boolean = false,
)

/** recovered from com/youloft/common/bean/Sender.java */
data class Sender(
    val nickName: String? = null,
    val userId: Long = 0L,
)

/** recovered from com/youloft/common/bean/SensBean.java */
data class SensBean(
    val address: Address? = null,
    val answerCount: Int? = null,
    val appIcon: String? = null,
    val appName: String? = null,
    val battery: Int? = null,
    val count: Int? = null,
    val createdAt: Long? = null,
    val deviceName: String? = null,
    val duration: Long? = null,
    val errorAnswer: String? = null,
    val eventTime: Long = 0L,
    val friendIds: List<Long?>? = null,
    val isCharging: Boolean = false,
    val lastDeviceName: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val lockDuration: Long = 0L,
    val networkType: Int? = null,
    val permission: List<String?>? = null,
    val phone: String? = null,
    val sender: Sender? = null,
    val `type`: Int = 0,
    val updatedAt: Long? = null,
    val userId: Long = 0L,
    val voiceCover: String? = null,
    val voiceTitle: String? = null,
    val voiceUrl: String? = null,
    val wifi: String? = null,
)

/** recovered from com/youloft/watcher/bean/SensReportResult.java */
data class SensReportResult(
    val openPhoneTimes: Int = 0,
    val stayNHours: Boolean = false,
    val useApp: List<AppUsage?>? = null,
    val useAppCount: Int = 0,
    val useTime: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/privacy/SensSettings.java */
data class SensSettings(
    val key: String? = null,
    val value: Boolean = false,
)

/** recovered from com/youloft/watcher/bean/ShareVip.java */
data class ShareVip(
    val avatar: String? = null,
    val expireTime: Long? = null,
    val nickname: String? = null,
    val userId: Long? = null,
    val vipSubType: Int? = null,
    val vipType: Int? = null,
)

/** recovered from com/youloft/watcher/bean/StayInfo.java */
data class StayInfo(
    val arriveTime: Long = 0L,
    val city: String? = null,
    val district: String? = null,
    val duration: Long = 0L,
    val poiName: String? = null,
    val province: String? = null,
    val street: String? = null,
    val streetNumber: String? = null,
)

/** recovered from com/youloft/watcher/bean/im/TalkRiskInit.java */
data class TalkRiskInit(
    val imSuspend: Boolean = false,
    val realNameChecked: Boolean = false,
    val riskConfig: RiskConfig? = null,
    val riskLevel: Int = 0,
    val riskTypes: List<Int?>? = null,
)

/** recovered from com/youloft/watcher/bean/todo/TodoModel.java */
data class TodoModel(
    val avatar: String? = null,
    val content: String? = null,
    val f63418id: Long = 0L,
    val isFinish: Boolean = false,
    val listType: Int = 0,
    val nickname: String? = null,
    val sex: Int = 0,
    val userId: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/todo/TodoRsp.java */
data class TodoRsp(
    val importantAndUrgent: List<TodoModel?>? = null,
    val importantButNutAndUrgent: List<TodoModel?>? = null,
    val notImportantButUrgent: List<TodoModel?>? = null,
    val notImportantNotUrgent: List<TodoModel?>? = null,
    val notifyType: Int = 0,
)

/** recovered from com/youloft/watcher/bean/TosToken.java */
data class TosToken(
    val AccessKeyId: String? = null,
    val BucketName: String? = null,
    val CurrentTime: JsonElement? = null,
    val Endpoint: String? = null,
    val ExpiredTime: JsonElement? = null,
    val Host: String? = null,
    val Region: String? = null,
    val SecretAccessKey: String? = null,
    val SessionToken: String? = null,
)

/** recovered from com/youloft/watcher/bean/TrackCard.java */
data class TrackCard(
    val arriveAddress: String? = null,
    val arriveTime: Long = 0L,
    val isStop: Boolean = false,
    val leaveAddress: String? = null,
    val leaveTime: Long = 0L,
)

/** recovered from com/youloft/watcher/bean/TrackCardForStay.java */
data class TrackCardForStay(
    val stayNumToday: Int = 0,
    val stays: List<StayInfo?>? = null,
)

/** recovered from com/youloft/watcher/bean/TrackRecordBean.java */
data class TrackRecordBean(
    val arrivalPointCount: Int = 0,
    val friendLocation: FriendLocation? = null,
    val permission: PermissionUpdateBean? = null,
    val points: List<JsonElement?>? = null,
    val totalDistance: Double = 0.0,
    val totalStayTime: Int = 0,
    val tracks: List<JsonElement?>? = null,
    val userId: Long = 0L,
    val distance: Double = 0.0,
    val duration: Long = 0L,
    val `type`: Int = 0,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val timePeriod: Long = 0L,
    val address: String? = null,
    val line: JsonElement? = null,
    val lostType: Int = 0,
)

/** recovered from com/youloft/watcher/bean/TrackWidget.java */
data class TrackWidget(
    val sensTrack: Boolean = false,
    val serverTime: Long = 0L,
    val stayNumToday: Int = 0,
    val stays: List<StayInfo?>? = null,
    val sysLocation: Boolean = false,
)

/** recovered from com/youloft/watcher/bean/User.java */
data class User(
    val anniversary: Anniversary? = null,
    val avatar: String? = null,
    val birthday: Birthday? = null,
    val getuiCid: String? = null,
    val hadFriend: Boolean = false,
    val hadVipPlan: Boolean = false,
    val imPassword: String? = null,
    val isRegister: Boolean = false,
    val mainUser: Long = 0L,
    val nickname: String? = null,
    val phone: String? = null,
    val registerAv: String? = null,
    val sex: Int = 0,
    val shareList: List<ShareVip?>? = null,
    val shareToMeList: List<ShareVip?>? = null,
    val showSkipButton: Boolean = false,
    val stage: Int = 0,
    val token: String? = null,
    val userId: Long = 0L,
    val vipExpireTime: Long = 0L,
    val vipSubType: Int = 0,
    val vipType: Int = 0,
)

/** recovered from com/youloft/watcher/bean/vip/VipData.java */
data class VipData(
    val expireTime: Long = 0L,
    val inductionType: Int = 0,
    val oneFold: Boolean = false,
    val productList: List<Product?>? = null,
    val randomBroadcast: List<JsonElement?>? = null,
    val retainCountdown: Long = 0L,
    val vipType: Int = 0,
    val avatar: String? = null,
    val nickname: String? = null,
    val productName: String? = null,
)

/** recovered from com/youloft/watcher/bean/Voice.java */
data class Voice(
    val cover: String? = null,
    val duration: Int? = null,
    val f63412id: Int = 0,
    val isCustom: Boolean = false,
    val localUri: String? = null,
    val sort: Int = 0,
    val status: Int = 0,
    val title: String? = null,
    val voice: String? = null,
)

/** recovered from com/youloft/watcher/bean/VoiceRsp.java */
data class VoiceRsp(
    val commonList: List<Voice?>? = null,
    val friendList: List<RealtimeVoiceFriendBean?>? = null,
    val myList: List<Voice?>? = null,
    val remainTimes: Int = 0,
    val sendLimit: Int = 0,
)

/** recovered from com/youloft/watcher/bean/WeatherBean.java */
data class WeatherBean(
    val now: JsonElement? = null,
    val feels_like: Int = 0,
    val f63413rh: Int = 0,
    val temp: Int = 0,
    val text: String? = null,
    val uptime: String? = null,
    val wind_class: String? = null,
    val wind_dir: String? = null,
)

/** recovered from com/youloft/watcher/bean/vip/WxPay.java */
data class WxPay(
    val appId: String? = null,
    val noncestr: String? = null,
    val partnerid: String? = null,
    val payPackage: String? = null,
    val prepayid: String? = null,
    val sign: String? = null,
    val timestamp: String? = null,
)
