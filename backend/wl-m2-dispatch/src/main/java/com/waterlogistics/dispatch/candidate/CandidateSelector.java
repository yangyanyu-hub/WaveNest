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
        // 组装逻辑收敛到 CandidateInputAssembler（Open CodeReview：两份同源副本
        // 必须收敛成一份，否则每次评审修复都要改两处漏一处）。mapper 查询口径
        // （status=空闲 + health_status=健康）仍留在本类。
        return CandidateInputAssembler.assemble(order, drones, nests);
    }
}
