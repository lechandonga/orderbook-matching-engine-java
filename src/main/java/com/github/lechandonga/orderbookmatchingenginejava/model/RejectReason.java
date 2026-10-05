package com.github.lechandonga.orderbookmatchingenginejava.model;

/**
 * 可区分的拒单原因。外部接口与内部调用共用同一套编码，保证判定一致、可解释。
 */
public enum RejectReason {
    FIELD_MISSING("字段缺失"),
    UNKNOWN_ACCOUNT("账户不存在"),
    UNKNOWN_SYMBOL("未知交易品种"),
    UNSUPPORTED_SIDE("不支持的买卖方向"),
    UNSUPPORTED_ORDER_TYPE("不支持的订单类型"),
    INVALID_QTY("数量非法，需为 1 ~ 1_000_000_000 的整数"),
    INVALID_PRICE("价格越界，限价单价格需大于 0 且不超过 1_000_000"),
    MARKET_PRICE_FORBIDDEN("市价单不允许指定价格"),
    INSUFFICIENT_AVAILABLE_CASH("可用资金不足"),
    INSUFFICIENT_AVAILABLE_POSITION("可用持仓不足"),
    EXPOSURE_LIMIT_EXCEEDED("买入敞口超过账户上限"),
    NO_LIQUIDITY("市价单无对手盘可成交"),
    ORDER_NOT_FOUND("订单不存在"),
    ORDER_NOT_ACTIVE("订单已成交/已撤销/已拒单，不可再撤"),
    CROSS_ACCOUNT_CANCEL("只能撤销本账户的订单"),
    IDEMPOTENCY_CONFLICT("同一外部订单号提交了不同内容"),
    EVENT_LOG_CORRUPTED("事件记录缺失或损坏");

    private final String description;

    RejectReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
