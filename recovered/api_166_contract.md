# LinkUp 1.6.6 API contract (recovered)

97 routes recovered from `gh/a.java` (R8-renamed Retrofit interface). `Signature` = hex(AES-128-CBC(PKCS7)) of `path?query`, see net/SignatureInterceptor.kt.

| verb | path | rebuilt method | original bean | params | dex name |
|---|---|---|---|---|---|
| GET | `api/relation/pre_confirm` | `relationPreConfirm` | `Friend` | query code: Long | `A` |
| GET | `api/setting/all_devices` | `settingAllDevices` | `Map<String, String>` | - | `A0` |
| GET | `api/talk/enshrine_list` | `talkEnshrineList` | `ChatCollectionData` | - | `B` |
| POST | `api/user/update/push/token` | `userUpdatePushToken` | `o2` | body: JSON map | `B0` |
| POST | `api/phone/state/report` | `phoneStateReport` | `o2` | body: JSON map | `C` |
| GET | `api/version/check` | `versionCheck` | `HashMap<String, Object>` | query queryId: String | `C0` |
| GET | `api/sensitive/server_timer` | `sensitiveServerTimer` | `ListBean<SensBean>` | query eventTime: Long | `D` |
| POST | `api/user/show/report` | `userShowReport` | `o2` | body: JSON map | `D0` |
| POST | `api/feedback` | `feedback` | `o2` | body: JSON map | `E` |
| GET | `api/anniversary/choose` | `anniversaryChoose` | `Anniversary` | query userId: Long; query friendId: Long; query friendAvatar: String | `E0` |
| GET | `api/four_quadrant_list/get_list` | `fourQuadrantListGetList` | `TodoRsp` | query listType: Int | `F0` |
| POST | `api/relation/lock` | `relationLock` | `o2` | body: JSON map | `G` |
| POST | `/api/feedback/create` | `apiFeedbackCreate` | `EvaluateRes` | body: JSON map | `G0` |
| POST | `api/relation/confirm` | `relationConfirm` | `ConfirmAdd` | query code: Long; body: JSON map | `H` |
| POST | `api/user/report/getui_cid` | `userReportGetuiCid` | `o2` | body: JSON map | `H0` |
| POST | `api/setting/update` | `settingUpdate` | `o2` | body: JSON map | `I` |
| POST | `api/relation/lock/cover/del` | `relationLockCoverDel` | `o2` | - | `I0` |
| GET | `api/sensitive/user` | `sensitiveUser` | `List<SensBean>` | query date: String; query mainUser: Long | `J` |
| GET | `api/setting/user_list_v2` | `settingUserListV2` | `PrivacyRes` | query pageSize: Int | `J0` |
| POST | `api/anniversary/createOrEdit` | `anniversaryCreateoredit` | `Anniversary` | body: JSON map | `K` |
| GET | `api/client/config` | `clientConfig` | `ChannelConfig` | - | `K0` |
| POST | `api/index/realtime/voice/delete` | `indexRealtimeVoiceDelete` | `o2` | body: JSON map | `L` |
| POST | `api/location/batch/report` | `locationBatchReport` | `o2` | body: JSON map | `L0` |
| GET | `api/pop/homePage` | `popHomepage` | `PopListBean` | - | `M0` |
| POST | `api/talk/enshrine_delete` | `talkEnshrineDelete` | `ChatCollectionData` | body: JSON map | `N` |
| GET | `api/sensitive/user/days` | `sensitiveUserDays` | `List<String>` | - | `N0` |
| GET | `api/feedback/checkPop` | `feedbackCheckpop` | `EvaluateRes` | query userId: Long | `O` |
| GET | `api/relation/lock_list` | `relationLockList` | `ListBean<LockInfo>` | - | `O0` |
| GET | `api/widget/track` | `widgetTrack` | `TrackWidget` | - | `P` |
| GET | `api/location/days` | `locationDays` | `List<String>` | query queryId: Long | `P0` |
| POST | `api/sms/click_to_login` | `smsClickToLogin` | `User` | body: JSON map | `Q` |
| POST | `api/risk/real_name_auth` | `riskRealNameAuth` | `o2` | body: JSON map | `Q0` |
| POST | `api/relation/update_remark` | `relationUpdateRemark` | `o2` | body: JSON map | `R` |
| GET | `api/address/tag_list` | `addressTagList` | `List<AddressTag>` | - | `R0` |
| GET | `/api/pay/check/order_status` | `apiPayCheckOrderStatus` | `OrderStatus` | query orderId: String | `S` |
| GET | `api/volcano/token` | `volcanoToken` | `TosToken` | - | `S0` |
| GET | `api/relation/info` | `relationInfo` | `FriendInfo` | query queryId: Long | `T` |
| POST | `api/user/logout` | `userLogout` | `o2` | - | `T0` |
| GET | `api/setting/friend_sens` | `settingFriendSens` | `PermissionUpdateBean` | query friendId: String | `U` |
| GET | `api/talk/init` | `talkInit` | `TalkRiskInit` | - | `U0` |
| GET | `api/tool/rsa/public_key` | `toolRsaPublicKey` | `PublicKey` | - | `V` |
| POST | `api/phone/app_use/report` | `phoneAppUseReport` | `o2` | body: JSON map | `W` |
| POST | `api/user/login` | `userLogin` | `User` | body: JSON map | `X` |
| POST | `api/phone/app_use/v2/report` | `phoneAppUseV2Report` | `o2` | body: JSON map | `Y` |
| POST | `api/relation/unlock` | `relationUnlock` | `o2` | body: JSON map | `Z` |
| POST | `api/phone/sys_screen/report` | `phoneSysScreenReport` | `o2` | body: JSON map | `a` |
| GET | `api/index` | `index` | `HomeData` | - | `a0` |
| GET | `api/user/default_avatar` | `userDefaultAvatar` | `AvatarList` | - | `b` |
| GET | `api/talk/enshrine` | `talkEnshrine` | `ChatCollectionData` | query image: String; query userId: Long; query hash: String | `b0` |
| GET | `api/setting/user_list` | `settingUserList` | `PrivacySettingList` | query pageSize: Int | `c` |
| POST | `/api/attribution/choice_channel` | `apiAttributionChoiceChannel` | `o2` | body: JSON map | `c0` |
| GET | `/api/pay/available_types` | `apiPayAvailableTypes` | `PayTypes` | - | `d` |
| GET | `api/vip/index` | `vipIndex` | `VipData` | - | `d0` |
| POST | `api/index/realtime/voice/send` | `indexRealtimeVoiceSend` | `o2` | body: JSON map | `e` |
| GET | `api/pay/transaction` | `payTransaction` | `Pay` | query productId: String; query fromScene: Int; query payType: Int; query gid: Int; query giftUserId: Long | `e0` |
| POST | `api/user/change_bind` | `userChangeBind` | `User` | body: JSON map | `f` |
| GET | `api/talk/gifs` | `talkGifs` | `ChatGifData` | query page: Int; query pageSize: Int | `f0` |
| POST | `api/location/report` | `locationReport` | `Location` | body: JSON map | `g` |
| POST | `api/risk/report` | `riskReport` | `o2` | body: JSON map | `g0` |
| GET | `api/talk/phone_state` | `talkPhoneState` | `HashMap<String, Object>` | query mainUser: Long | `h` |
| POST | `/api/four_quadrant_list/del` | `apiFourQuadrantListDel` | `o2` | body: JSON map | `h0` |
| POST | `/api/relation/apply` | `apiRelationApply` | `FriendApply` | body: JSON map | `i` |
| POST | `/api/vip/share_to_friend` | `apiVipShareToFriend` | `o2` | body: JSON map | `i0` |
| POST | `api/four_quadrant_list/edit` | `fourQuadrantListEdit` | `o2` | body: JSON map | `j` |
| POST | `api/attribution/activation` | `attributionActivation` | `ActivationResult` | body: JSON map | `j0` |
| GET | `api/screenshot/done` | `screenshotDone` | `Boolean` | query friendId: Long; query SocializeConstants.KEY_PIC: String | `k` |
| POST | `api/address/tag` | `addressTag` | `Object` | body: JSON map | `k0` |
| GET | `api/user/info` | `userInfo` | `User` | - | `l` |
| GET | `api/widget/location` | `widgetLocation` | `LocationWidget` | - | `l0` |
| POST | `api/setting/update` | `settingUpdate2` | `o2` | body: JSON map | `m` |
| GET | `/api/relation/apply_list` | `apiRelationApplyList` | `FriendRequestList` | query page: Int; query pageSize: Int | `m0` |
| POST | `api/talk/enshrine_top` | `talkEnshrineTop` | `ChatCollectionData` | body: JSON map | `n` |
| GET | `api/feedback/check` | `feedbackCheck` | `FeedbackPopBean` | query userId: Long; query deviceId: String | `n0` |
| GET | `api/relation/lock/question_list` | `relationLockQuestionList` | `ListBean<QuestionBean>` | - | `o` |
| GET | `api/ad_config/list` | `adConfigList` | `AdConfigRes` | - | `o0` |
| GET | `api/setting/im` | `settingIm` | `AutoReport` | - | `p` |
| POST | `api/user/android/install_app` | `userAndroidInstallApp` | `o2` | body: JSON map | `p0` |
| GET | `api/weather/baiduWeather` | `weatherBaiduweather` | `WeatherBean` | query data_type: String; query district_id: String; query force: Int | `q` |
| POST | `api/user/update/info` | `userUpdateInfo` | `User` | body: JSON map | `q0` |
| GET | `api/relation/lock/cover/list` | `relationLockCoverList` | `ListBean<LockImage>` | - | `r` |
| GET | `api/screenshot/list` | `screenshotList` | `ListBean<ScreenshotData>` | - | `r0` |
| GET | `api/sms/login/cut` | `smsLoginCut` | `Cut` | - | `s` |
| GET | `api/index/realtime/voice/friend/list` | `indexRealtimeVoiceFriendList` | `VoiceRsp` | - | `s0` |
| GET | `api/user/cut/list` | `userCutList` | `CutConfigBean` | - | `t` |
| GET | `api/location/v2/tracks` | `locationV2Tracks` | `TrackRecordBean` | query queryId: Long; query date: String | `t0` |
| POST | `api/relation/del` | `relationDel` | `o2` | body: JSON map | `u` |
| GET | `api/relation/list` | `relationList` | `FriendList` | query page: Int; query pageSize: Int; query key: String | `u0` |
| POST | `api/phone/call_record/report` | `phoneCallRecordReport` | `o2` | body: JSON map | `v` |
| GET | `api/sms/send` | `smsSend` | `o2` | query phone: String; query region: String | `v0` |
| POST | `api/relation/update` | `relationUpdate` | `o2` | body: JSON map | `w` |
| POST | `api/tool/uploadLog` | `toolUploadlog` | `String` | body: JSON map | `w0` |
| POST | `api/relation/main_user` | `relationMainUser` | `o2` | body: JSON map | `x` |
| GET | `api/index/realtime/voice/list` | `indexRealtimeVoiceList` | `VoiceRsp` | - | `x0` |
| POST | `/api/relation/match_by_phone` | `apiRelationMatchByPhone` | `SearchFriend` | body: JSON map | `y` |
| POST | `api/sensitive/report` | `sensitiveReport` | `SensReportResult` | body: JSON map | `y0` |
| POST | `api/user/unregister` | `userUnregister` | `o2` | - | `z` |
| POST | `api/index/realtime/voice/upload` | `indexRealtimeVoiceUpload` | `o2` | body: JSON map | `z0` |
