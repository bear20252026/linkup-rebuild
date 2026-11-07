// Package dto mirrors the JSON shapes the recovered Android client parses. Field names are the
// Kotlin property names — R8 stripped every @SerializedName in both shipped APKs while Gson kept
// working, which is what proves the wire names equal the field names (report/08).
//
// Pointers and `omitempty` are deliberate: the client's models were generated with nullable
// reference fields because Gson inserts null for an absent field even where the original Kotlin
// said non-null. Returning a missing field as null is therefore the compatible answer, and omitting
// one that the client treats as absent is not the same thing.
package dto

import "linkup.local/server/internal/location"

// Envelope is the body wrapper every route answers with.
type Envelope struct {
	Status int    `json:"status"`
	Msg    string `json:"msg"`
	Data   any    `json:"data,omitempty"`
}

// User is `bean/User`, the login answer and the cached `user2` record.
type User struct {
	Anniversary    any     `json:"anniversary,omitempty"`
	Avatar         *string `json:"avatar"`
	Birthday       any     `json:"birthday,omitempty"`
	GetuiCid       *string `json:"getuiCid"`
	HadFriend      bool    `json:"hadFriend"`
	HadVipPlan     bool    `json:"hadVipPlan"`
	IMPassword     *string `json:"imPassword"`
	IsRegister     bool    `json:"isRegister"`
	MainUser       int64   `json:"mainUser"`
	Nickname       *string `json:"nickname"`
	Phone          *string `json:"phone"`
	RegisterAv     *string `json:"registerAv"`
	Sex            int     `json:"sex"`
	ShareList      any     `json:"shareList,omitempty"`
	ShareToMeList  any     `json:"shareToMeList,omitempty"`
	ShowSkipButton bool    `json:"showSkipButton"`
	Stage          int     `json:"stage"`
	Token          *string `json:"token"`
	UserID         int64   `json:"userId"`
	VipExpireTime  int64   `json:"vipExpireTime"`
	VipSubType     int     `json:"vipSubType"`
	VipType        int     `json:"vipType"`
}

// Permission is the nine sharing switches the confirm sheet collects.
type Permission struct {
	SensAppUse        bool `json:"sensAppUse"`
	SensCall          bool `json:"sensCall"`
	SensLocation      bool `json:"sensLocation"`
	SensLockPhone     bool `json:"sensLockPhone"`
	SensPhoneState    bool `json:"sensPhoneState"`
	SensPhoneUse      bool `json:"sensPhoneUse"`
	SensRealtimeVoice bool `json:"sensRealtimeVoice"`
	SensScreenShot    bool `json:"sensScreenShot"`
	SensSensitive     bool `json:"sensSensitive"`
	SensTrack         bool `json:"sensTrack"`
}

// Friend is the "stranger I could add" record pre_confirm answers with.
type Friend struct {
	Avatar              *string     `json:"avatar"`
	IsFriendLimit       bool        `json:"isFriendLimit"`
	Nickname            *string     `json:"nickname"`
	OtherFriendLimit    bool        `json:"otherFriendLimit"`
	OtherRiskLevel      *int        `json:"otherRiskLevel"`
	Permission          *Permission `json:"permission"`
	RiskLevel           *int        `json:"riskLevel"`
	Sex                 *string     `json:"sex"`
	SslCertificateError bool        `json:"sslCertificateError"`
	UserID              int64       `json:"userId"`
}

// FriendApply is the answer to `relation/apply`.
type FriendApply struct {
	ApplyStatus int     `json:"applyStatus"`
	Avatar      *string `json:"avatar"`
	FriendID    int64   `json:"friendId"`
	Nickname    *string `json:"nickname"`
	Sex         *string `json:"sex"`
	UserID      int64   `json:"userId"`
}

// ConfirmAdd is the answer to `relation/confirm`; friendLimit 0 means "go ahead".
type ConfirmAdd struct {
	FriendLimit int   `json:"friendLimit"`
	FriendID    int64 `json:"friendUserId"`
}

// FriendRequest is one row of the request list.
type FriendRequest struct {
	Avatar        *string `json:"avatar"`
	Birthday      any     `json:"birthday,omitempty"`
	CreateAt      int64   `json:"createAt"`
	IsFriendLimit bool    `json:"isFriendLimit"`
	IsUnregister  bool    `json:"isUnregister"`
	Nickname      *string `json:"nickname"`
	Relation      int     `json:"relation"`
	RiskLevel     *int    `json:"riskLevel"`
	RiskTypes     any     `json:"riskTypes,omitempty"`
	Sex           int     `json:"sex"`
	Status        int     `json:"status"`
	UserID        int64   `json:"userId"`
}

// FriendRequestList is the paged request list with its unread badge.
type FriendRequestList struct {
	IsFriendLimit *bool            `json:"isFriendLimit"`
	LastApplyUser any              `json:"lastApplyUser,omitempty"`
	List          []*FriendRequest `json:"list"`
	UnreadCount   *int             `json:"unreadCount"`
}

// FriendRow is one row of "my friends". This shape is `bean/FriendList.Info`, which is NOT the same
// bean as the detail screen's `FriendInfo`: here `relation` and `sex` are numbers, the timestamp key
// is `createAt` (seconds, because the adapter does `Date(createAt * 1000L)` at
// FriendListVAdapter.kt:80), and there is no `permission` object at all.
//
// Fields the shipped client declares but this backend has no value for are left out so Gson applies
// its own defaults, rather than invented: `av`, `deleteAt`, `id`, `imSuspend`, `location`, `newFriend`,
// `permissionValue` (bound to a row checkbox — looks like transient UI state), `receivedFirstMsg`,
// `remarkToMe`, `riskTypes`, `selected`, `sendFirstMsg`, `unregisterTime`. They are listed as unknowns
// in the README; emitting a guess would look identical to a recovered value.
type FriendRow struct {
	Avatar    *string `json:"avatar"`
	CreateAt  int64   `json:"createAt"`
	Nickname  *string `json:"nickname"`
	Relation  int     `json:"relation"`
	Remark    *string `json:"remark"`
	RiskLevel *int    `json:"riskLevel"`
	Sex       int     `json:"sex"`
	UserID    int64   `json:"userId"`
	VipExpire int64   `json:"vipExpireTime"`
}

// FriendList is `api/relation/list`'s answer.
type FriendList struct {
	List []*FriendRow `json:"list"`
}

// FriendInfo is the friend detail screen's answer.
type FriendInfo struct {
	Avatar           *string     `json:"avatar"`
	Birthday         any         `json:"birthday,omitempty"`
	Client           *string     `json:"client"`
	CreateAt         int64       `json:"createAt"`
	Nickname         *string     `json:"nickname"`
	OtherFriendLimit bool        `json:"otherFriendLimit"`
	Permission       *Permission `json:"permission"`
	Relation         *string     `json:"relation"`
	Remark           *string     `json:"remark"`
	Sex              *string     `json:"sex"`
	UserID           int64       `json:"userId"`
}

// Cut is the login-type switch; a string, not a number.
type Cut struct {
	LoginType *string `json:"loginType"`
}

// PublicKey is the RSA envelope key, in the form the client strips.
type PublicKey struct {
	PublicKey *string `json:"publicKey"`
}

// ChannelConfig is `api/client/config`'s answer. The nine fields are everything either shipped APK
// models; production answers twelve, and those three extra keys have never been read by any client
// (report/06 records the mismatch as an open product decision).
type ChannelConfig struct {
	AutoReportAddressShow int    `json:"autoReportAddressShow"`
	HalfScreenVipPageShow bool   `json:"halfScreenVipPageShow"`
	ImGroupHonor          string `json:"imGroupHonor"`
	ImGroupHuawei         string `json:"imGroupHuawei"`
	ImGroupOppo           string `json:"imGroupOppo"`
	ImGroupVivo           string `json:"imGroupVivo"`
	ImGroupXiaomi         string `json:"imGroupXiaomi"`
	NotRelativesLockShow  bool   `json:"notRelativesLockShow"`
	VipMarketingCopy      string `json:"vipMarketingCopy"`
}

// Text returns a pointer to s, the shape most nullable JSON fields need.
func Text(s string) *string { return &s }

// Int returns a pointer to n.
func Int(n int) *int { return &n }

// FriendLocation is bean/FriendLocation: the guarded person's position as the track screen shows it.
//
// `endMeetingTime`, `lastMeetingTime` and `pairTime` belong to the pairing/meeting model this
// backend does not have yet, so they stay 0 rather than invented; `arrivalTime` is the fix's own
// timestamp, which is the only reading the client's "更新于" label can mean.
type FriendLocation struct {
	Address         *location.Address `json:"address,omitempty"`
	ArrivalTime     int64             `json:"arrivalTime"`
	EndMeetingTime  int64             `json:"endMeetingTime"`
	LastMeetingTime int64             `json:"lastMeetingTime"`
	PairTime        int64             `json:"pairTime"`
	Lat             float64           `json:"lat"`
	Lng             float64           `json:"lng"`
	Speed           float32           `json:"speed"`
	Type            int               `json:"type"`
}

// TrackRecord is bean/TrackRecordBean, `api/location/v2/tracks`'s answer. The nested
// Point/Track/Line types come straight from internal/location so the wire shape cannot drift from
// the code that computes it.
type TrackRecord struct {
	ArrivalPointCount int                      `json:"arrivalPointCount"`
	FriendLocation    *FriendLocation          `json:"friendLocation,omitempty"`
	Permission        *Permission              `json:"permission,omitempty"`
	Points            []location.PolylinePoint `json:"points"`
	TotalDistance     float64                  `json:"totalDistance"`
	TotalStayTime     int64                    `json:"totalStayTime"`
	Tracks            []location.Track         `json:"tracks"`
	UserID            int64                    `json:"userId"`
}

// ImToken is `api/user/im_token`'s answer: the OpenIM identity and token the client SDK logs in with,
// plus the msg_gateway address to connect to. Field names match the client's Gson model (Dtos.kt) —
// the JSON key is the Kotlin property name, the same rule every recovered bean followed.
type ImToken struct {
	ImUserId          string `json:"imUserId"`
	ImToken           string `json:"imToken"`
	ExpireTimeSeconds int64  `json:"expireTimeSeconds"`
	WsUrl             string `json:"wsUrl"`
}


// LockInfoRow is the client's bean/lock/LockInfo (api/relation/lock_list). av/client ride the
// phone-state card and stay empty until it lands.
type LockInfoRow struct {
	UserID            int64          `json:"userId"`
	Remark            *string        `json:"remark"`
	Nickname          *string        `json:"nickname"`
	Sex               int            `json:"sex"`
	Avatar            *string        `json:"avatar"`
	Av                *string        `json:"av"`
	Client            *string        `json:"client"`
	Relation          int            `json:"relation"`
	CreateAt          int64          `json:"createAt"`
	ServerTime        int64          `json:"serverTime"`
	LockTime          int64          `json:"lockTime"`
	LockDuration      int64          `json:"lockDuration"`
	LockTitle         *string        `json:"lockTitle"`
	LockBackPic       *string        `json:"lockBackPic"`
	Question          *string        `json:"question"`
}

type LockInfoList struct {
	List []*LockInfoRow `json:"list"`
}

// QuestionRow is the client's bean/lock/QuestionBean; the bank content is a rebuild choice.
type QuestionRow struct {
	Content       string                `json:"content"`
	Options       []*QuestionOptionRow  `json:"options"`
	CorrectAnswer string                `json:"correctAnswer"`
}

type QuestionOptionRow struct {
	Label   string `json:"label"`
	Content string `json:"content"`
}

type QuestionList struct {
	List []QuestionRow `json:"list"`
}

// LockImageRow is the client's bean/lock/LockImage; the cover bank answers empty until a media
// store exists (待验 77).
type LockImageRow struct {
	ID      int    `json:"id"`
	UserID  int    `json:"userId"`
	Image   string `json:"image"`
	Tinyimg string `json:"tinyimg"`
	Client  string `json:"client"`
}

type LockImageList struct {
	List []*LockImageRow `json:"list"`
}
