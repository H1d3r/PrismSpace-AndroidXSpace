package com.yzddmr6.prismspace.prism.compose.component

// 状态等级语义枚举。被 StatusHeroCard / StatusTag / StatusRow 等共用。
// Neutral = 加载中/检查中/未知/无偏向信息；「不知道」绝不渲染为 Ok（绿）或 Warn（黄）。
enum class PrismLevel { Neutral, Ok, Warn, Error }
