package com.waterlogistics.dispatch.candidate;

import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 候选输入组装器——{@code CandidateFeasibilityPlanner} 与 {@code CandidateSelector}
 * 共用的唯一组装实现（Open CodeReview：两份同源副本必须收敛成一份，
 * 否则每次评审修复都要改两处、漏一处）。
 *
 * <p>职责边界：mapper 查询（含 health/status 过滤口径）留在各调用方，
 * 本类只负责<b>查出来的行 → {@link CandidateInput}</b> 的纯组装。
 */
public final class CandidateInputAssembler {

    private CandidateInputAssembler() {
    }

    /**
     * 组装候选输入。
     *
     * @throws IllegalStateException 订单 {@code ship_lon}/{@code ship_lat}/{@code goods_weight} 缺失
     *                               （评审 MEDIUM.13：不填 0 兜底，避免造出 (0,0) 取货点）
     */
    public static CandidateInput assemble(OrderTaskEntity order,
                                          List<DroneEntity> drones,
                                          List<NestEntity> nests) {
        // 取货点与载荷缺失即快速失败（评审 MEDIUM.13）：原 0.0 兜底把取货点挪到
        // 几内亚湾 (0,0)，距离全 9000+ km，候选被 maxNestDistanceKm 全数过滤——
        // 现场只看到「无可用候选」，真实原因（订单缺经纬度）被埋掉。宁可抛，让上游修数据。
        requirePresentOrderField("ship_lon", order.getShipLon());
        requirePresentOrderField("ship_lat", order.getShipLat());
        requirePresentOrderField("goods_weight", order.getGoodsWeight());
        double pickupLon = order.getShipLon().doubleValue();
        double pickupLat = order.getShipLat().doubleValue();
        double payloadKg = order.getGoodsWeight().doubleValue();

        List<CandidateInput.NestView> nestViews = new java.util.ArrayList<>();
        for (NestEntity n : nests) {
            nestViews.add(new CandidateInput.NestView(
                    n.getNestId(),
                    // 机巢坐标缺失按 0.0 立此存照（Open CodeReview 未点名，但属 MEDIUM.13 同款）：
                    // 缺坐标的巢会被挪到 (0,0) 然后被距离上限淘汰——结果方向安全（不会派向假巢），
                    // 但静默。机巢是系统台账数据而非订单数据，不宜抛单；缺失应修台账。
                    n.getLon() != null ? n.getLon().doubleValue() : 0.0,
                    n.getLat() != null ? n.getLat().doubleValue() : 0.0,
                    splitModels(n.getSupportedModels())));
        }

        List<CandidateInput.DroneView> droneViews = new java.util.ArrayList<>();
        for (DroneEntity d : drones) {
            droneViews.add(new CandidateInput.DroneView(
                    d.getDroneId(),
                    d.getCurrentNestId() != null ? d.getCurrentNestId() : d.getHomeNestId(),
                    d.getModel(),
                    d.getStatus(),
                    // battery/maxPayload 缺失按 0.0＝有意保守（缺数据当"没电/载不动"，被闸淘汰）。
                    // 切不可学 windLimit 的 NaN 口径——NaN 参与 > / < 比较恒 false，
                    // 会让没电的机器通过电量闸（那才是危险方向）。缺失数据在台账里补。
                    d.getBattery() != null ? d.getBattery().doubleValue() : 0.0,
                    d.getMaxPayload() != null ? d.getMaxPayload().doubleValue() : 0.0,
                    // 机型能力两列：DB 缺失时给 NaN 表示"未登记"，
                    // 由 FeasibilityJudge 按保守回落口径处理——既不臆造 0，也不填默认值冒充真值
                    optionalDouble(d.getWindLimitMs()),
                    optionalDouble(d.getCruiseSpeedMs())));
        }

        return new CandidateInput(pickupLon, pickupLat, payloadKg, nestViews, droneViews);
    }

    /** {@code BigDecimal} → {@code double}；为 {@code null} 时给 {@code NaN}（＝未登记，不是 0）。 */
    private static double optionalDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : Double.NaN;
    }

    /**
     * 订单必需字段缺失即抛（评审 MEDIUM.13）——不填 0.0 造出 (0, 0) 取货点。
     *
     * @throws IllegalStateException 字段为 {@code null}
     */
    private static void requirePresentOrderField(String field, BigDecimal value) {
        if (value == null) {
            throw new IllegalStateException(
                    "订单缺少必要字段 " + field + "，无法组装候选输入（不填 0 兜底，避免造出 (0,0) 取货点）");
        }
    }

    /**
     * 机巢允许机型串 → 机型列表：逐个 {@code trim} 并滤掉空串（评审 HIGH.7 / MEDIUM.18）。
     *
     * <p>不 trim 的后果：台账写 {@code "M30T, M400"} 时拆出 {@code " M400"}，
     * 与机型名精确比较不等，该机型被静默排除（「机巢明明支持却不出候选」）；
     * 空串拆出 {@code [""]} 会被当成「任何机型都不允许」，该巢候选静默清零。
     * 统一归一为空列表＝未登记＝不限制机型。
     */
    public static List<String> splitModels(String supportedModels) {
        if (supportedModels == null || supportedModels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(supportedModels.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
