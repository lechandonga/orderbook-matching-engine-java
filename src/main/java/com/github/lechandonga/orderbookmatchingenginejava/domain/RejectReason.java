package com.github.lechandonga.orderbookmatchingenginejava.domain;

/**
 * 可区分的拒单原因。
 * HTTP 入口会映射为不同的 4xx 状态码，内部入口通过 code 字段返回同样的枚举。
 */
public enum RejectReason {
    MISSING_FIELD("字段缺失或为空"),
    INVALID_PRICE("限价单价格非法或越界（必须为正数，上限见应用配置）"),
    INVALID_QUANTITY("数量非法或越界（必须为正整数，上下限见应用配置）"),
    MARKET_PRICE_NOT_ALLOWED("市价单不允许指定价格"),
    UNKNOWN_ACCOUNT("账户不存在"),
    UNKNOWN_SYMBOL("交易标的不存在"),
    INSUFFICIENT_CASH("买入预留资金超过账户可用额度"),
    EXPOSURE_LIMIT_EXCEEDED("单笔名义金额超过账户敞口上限"),
    INSUFFICIENT_POSITION("卖出数量超过账户可用持仓"),
    MARKET_NOT_TRADEABLE("市价单提交时对手盘无报价，按 IOC 撤余规则拒绝"),
    DUPLICATE_ORDER_ID("外部订单号冲突：与已存在订单的账户/方向/标的/类型/价格不一致"),
    ORDER_NOT_FOUND("订单不存在"),
    ORDER_NOT_ACTIVE("订单已终结（全部成交 / 已撤销 / 已拒绝），不可撤销"),
    CANCEL_ACCOUNT_MISMATCH("撤单账户与下单账户不一致"),
    LEDGER_CORRUPTED("事件记录损坏，无法保证重放一致性");

    private final String description;

    RejectReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
