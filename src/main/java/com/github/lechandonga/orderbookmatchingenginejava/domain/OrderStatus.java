package com.github.lechandonga.orderbookmatchingenginejava.domain;

/**
 * 订单生命周期状态。
 * NEW       已受理、尚未成交（已入簿）
 * PARTIALLY_FILLED 部分成交，剩余数量仍在簿
 * FILLED    全部成交
 * CANCELED  剩余部分被撤销（含市价单无对手盘 / 越过保护价时的 IOC 撤余）
 * REJECTED  未受理（参数非法 / 风控拒绝 / 幂等冲突），不产生任何账本影响
 */
public enum OrderStatus {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED
}
