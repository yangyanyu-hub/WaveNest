package com.waterlogistics.dispatch.candidate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.waterlogistics.algo.candidate.CandidateGenerator;
import com.waterlogistics.algo.candidate.CandidateInput;
import com.waterlogistics.storage.entity.DroneEntity;
import com.waterlogistics.storage.entity.NestEntity;
import com.waterlogistics.storage.entity.OrderTaskEntity;
import com.waterlogistics.storage.repository.DroneMapper;
import com.waterlogistics.storage.repository.NestMapper;
import com.waterlogistics.storage.param.SysParamLoader;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 候选方案综合择优（模块二 功能，10-16 简化版）。
 *
 * <p>从 DB 读空闲健康无人机与可用机巢，组装 {@link CandidateInput}，
 * 调序 3 的 {@link CandidateGenerator} 生成候选三元组，按距离最近择优
 * （简化目标：只看机巢→取货点直线距离；多目标精算顺延）。
 *
 * <p>约束：不做业务判断，只做数据装配 + 择优；阈值读 sys_param，不硬编码。
 */
@Service
public class CandidateSelector {

    private static final String DEFAULT_STATUS_IDLE = "空闲";
    private static final String DEFAULT_NEST_AVAILABLE = "可用";

    /** 健康状态词（照 {@code 001_init.sql} 列注释「健康/告警」） */
    private static final String DEFAULT_HEALTH_OK = "健康";

    private final DroneMapper droneMapper;
    private final NestMapper nestMapper;
    private final SysParamLoader sysParamLoader;
    private final CandidateGenerator candidateGenerator;

    public CandidateSelector(DroneMapper droneMapper, NestMapper nestMapper,
                             SysParamLoader sysParamLoader) {
        this.droneMapper = droneMapper;
        this.nestMapper = nestMapper;
        this.sysParamLoader = sysParamLoader;
        this.candidateGenerator = new CandidateGenerator();
    }

    /**
     * 为指定订单选最优候选组合。
     *
     * @return 最优候选；无可用候选时返回 null
     */
    public CandidateGenerator.CandidateCombo selectBest(OrderTaskEntity order) {
        List<DroneEntity> drones = droneMapper.selectList(
            new LambdaQueryWrapper<DroneEntity>()
                .eq(DroneEntity::getStatus, DEFAULT_STATUS_IDLE)
                // 健康状态过滤（评审 HIGH.6）：原查询只看「空闲」，告警机照样被当成可派候选。
                // 取值照 001_init.sql 列注释「健康/告警」，只放行「健康」；
                // 用 `=` 而非 `ne(告警)`，是为了让 NULL／拼错的值一律排除（白名单口径）。
                .eq(DroneEntity::getHealthStatus, DEFAULT_HEALTH_OK));
        List<NestEntity> nests = nestMapper.selectList(
            new LambdaQueryWrapper<NestEntity>()
                .eq(NestEntity::getStatus, DEFAULT_NEST_AVAILABLE));

        if (drones.isEmpty() || nests.isEmpty()) {
            return null;
        }

        CandidateInput input = buildInput(order, drones, nests);
        CandidateGenerator.Params params = new CandidateGenerator.Params(
            sysParamLoader.getDouble("minBatteryPercent", 30.0),
            sysParamLoader.getDouble("maxNestDistanceKm", 5.0));

        List<CandidateGenerator.CandidateCombo> combos = candidateGenerator.generate(input, params);
        if (combos.isEmpty()) {
            return null;
        }
        // CandidateGenerator 已按距离升序，取第一个即为最优（简化）
        return combos.get(0);
    }

    private CandidateInput buildInput(OrderTaskEntity order,
                                      List<DroneEntity> drones,
                                      List<NestEntity> nests) {
        // 取货点与载荷缺失即快速失败（评审 MEDIUM.13）：
        // 原实现给 0.0，等于把取货点挪到几内亚湾 (0,0)，算出的距离全是 9000+ km，
        // 候选被 maxNestDistanceKm 全数过滤 —— 现场只看到「无可用候选」，
        // 真实原因（订单缺经纬度）被埋掉。宁可抛，让上游修数据。
        requirePresentOrderField("ship_lon", order.getShipLon());
        requirePresentOrderField("ship_lat", order.getShipLat());
        requirePresentOrderField("goods_weight", order.getGoodsWeight());
        double pickupLon = order.getShipLon().doubleValue();
        double pickupLat = order.getShipLat().doubleValue();
        double payloadKg = order.getGoodsWeight().doubleValue();

        List<CandidateInput.NestView> nestViews = new ArrayList<>();
        for (NestEntity n : nests) {
            nestViews.add(new CandidateInput.NestView(
                n.getNestId(),
                n.getLon() != null ? n.getLon().doubleValue() : 0.0,
                n.getLat() != null ? n.getLat().doubleValue() : 0.0,
                splitModels(n.getSupportedModels())));
        }

        List<CandidateInput.DroneView> droneViews = new ArrayList<>();
        for (DroneEntity d : drones) {
            droneViews.add(new CandidateInput.DroneView(
                d.getDroneId(),
                d.getCurrentNestId() != null ? d.getCurrentNestId() : d.getHomeNestId(),
                d.getModel(),
                d.getStatus(),
                // battery/maxPayload 缺失按 0.0 处理是有意的保守策略（Open CodeReview 质疑后立此存照）：
                // 缺数据 ⇒ 当作"没电/载不动" ⇒ 被闸自然淘汰，方向安全。
                // 切不可学 windLimit 的 NaN 口径——NaN 参与 > / < 比较恒 false，
                // 会让没电的机器通过电量闸（那才是危险方向）。缺失数据在台账里补。
                d.getBattery() != null ? d.getBattery().doubleValue() : 0.0,
                d.getMaxPayload() != null ? d.getMaxPayload().doubleValue() : 0.0,
                // 机型能力两列（10-20 B1）：DB 缺失时给 NaN 表示"未登记"，
                // 由 FeasibilityJudge 按回退口径处理——这里既不臆造 0，也不填默认值冒充真值
                optionalDouble(d.getWindLimitMs()),
                optionalDouble(d.getCruiseSpeedMs())));
        }

        return new CandidateInput(pickupLon, pickupLat, payloadKg, nestViews, droneViews);
    }

    /** {@code BigDecimal} → {@code double}；为 {@code null} 时给 {@code NaN}（＝未登记） */
    private static double optionalDouble(BigDecimal v) {
        return v != null ? v.doubleValue() : Double.NaN;
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
     * 机巢允许机型串 → 机型列表：逐个 {@code trim} 并滤掉空串（评审 HIGH.7）。
     *
     * <p>不 trim 的后果：台账写 {@code "M30T, M400"} 时拆出 {@code " M400"}，
     * 与机型名 {@code "M400"} 用 {@code equals} 比较不等，该机型被静默排除，
     * 现场表现为「机巢明明支持却不出候选」。归一为空列表时表示「未登记、不限制机型」。
     */
    private static List<String> splitModels(String supportedModels) {
        if (supportedModels == null || supportedModels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(supportedModels.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
