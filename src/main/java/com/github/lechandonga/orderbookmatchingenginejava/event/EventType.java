package com.github.lechandonga.orderbookmatchingenginejava.event;

/**
 * 事件记录类型，即订单生命周期在事件日志中的全部状态迁移。
 *
 * ACCOUNT_OPENED   账户初始化（首次启动播种，幂等）
 * ORDER_ACCEPTED   新单通过参数与前置风控，进入撮合
 * ORDER_REJECTED   订单被拒（参数 / 风控 / 幂等冲突），仅记录，绝不改账本
 * TRADE            一笔撮合成交（taker 与 maker 各结算一次，由 tradeId 关联）
 * ORDER_CANCELED   撤单成功（含市价单 IOC 撤余）
 */
public enum EventType {
    ACCOUNT_OPENED,
    ORDER_ACCEPTED,
    ORDER_REJECTED,
    TRADE,
    ORDER_CANCELED
}
