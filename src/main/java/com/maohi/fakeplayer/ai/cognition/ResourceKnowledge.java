package com.maohi.fakeplayer.ai.cognition;

import com.maohi.MaohiConfig;
import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.Personality;

/**
 * V5.152: 资源公知层 —— 假人的「哪里有什么资源」常识表,单一事实源(single source of truth)。
 *
 * 设计动机(用户 2026-06-28):
 *   假人此前的「找资源」知识分散两处且不成体系:
 *     - 横向(去哪个 biome): {@link BiomePrior}(LOG/STONE/IRON/COAL/FOOD 亲和度,驱动 setExplore 选向)
 *     - 纵向(挖到哪个 Y): StripMineBehavior 里散落的 cfg.stripMineTargetY / stripMineDiamondTargetY
 *   缺一张「每种资源 = 最佳高度 + 最佳地点 + 找法」的统一公知表 → 假人对没接的资源毫无方向感,
 *   只能瞎跑。本表把这份常识集中、补全、明确化,作为「缺啥补啥 = 正常认知」的姊妹篇
 *   「哪里有啥 = 正常认知」(见 memory feedback-armor-gates-diamond 架构方向)。
 *
 * 知识来源 = 用户给的资源池公知(vanilla 1.21 矿物分布常识),并按「游戏实测/与现有守卫的咬合」校正:
 *
 *   资源       最佳高度        最佳地点          本表取值与备注
 *   ------    -----------    --------------    -----------------------------------------------
 *   木头       地表           森林/丛林/针叶林     SURFACE, biome→LOG
 *   石头       全地下          任意               DIG Y~0, biome→STONE(挖哪都有,深度不敏感)
 *   煤炭       Y>128 山地      山地              DIG —— *故意不取 128*:假人挖铁本就在 Y15,顺路捡煤
 *                                              比专程爬山到 Y128 高效得多(见 StripMine V5.143 专挖煤)
 *   铜         Y48 洞穴        洞穴              DIG Y48(进度暂不需,留作公知)
 *   铁         山地 / Y16     山地露天           DIG Y15, biome→IRON(与 cfg.stripMineTargetY 一致)
 *   金         Y-16 恶地       恶地(Mesa)        DIG Y-16, biome→恶地(进度暂不需)
 *   红石       Y-59 深层洞穴    深层              DIG Y-59(进度暂不需)
 *   钻石       Y-59 深板岩      深板岩           DIG —— *取 -54 不取 -59*:StripMine 底岩守卫在 Y≤-56
 *                                              abort,target 必须高于守卫,故 cfg.stripMineDiamondTargetY=-54
 *   下界合金    Y15 下界        下界岩浆区         NETHER Y15(PhaseNether 专属链处理)
 *
 * 工程原则(不盲从表):
 *   表是「理想分布」,本类按「假人当前进度链的实际收益 + 与现有守卫的安全咬合」落地。煤不爬山、
 *   钻石不下到底岩守卫以下,都是有意为之 —— 公知服务于「少瞎跑」,不是机械复刻 wiki 数字。
 *
 * 现状接入:
 *   - 纵向: {@link #stripMineTargetY} 供 StripMineBehavior 取 strip-mine 目标层(config 优先,回落本表)。
 *   - 横向: {@link #surfaceExploreBias} 供 PhaseUtil.setExplore/hostileEscapeYaw 取「该阶段朝哪个 biome 探索」
 *           (V5.153 统一两处重复映射,IRON_AGE 改偏山地);{@link Resource#biomeHint} 桥接到 BiomePrior。
 *   - 下挖判定: {@link #isWellAboveLayer} 供阶段判「想挖矿却在层之上」→ 改朝公知层下挖,不横向瞎逛。
 *   - 动态情报桥: {@link Resource#sharedLandmark} 把本表桥接到 {@link SharedResourceMap}「假人间共享:具体在哪」。
 *
 * 三层认知分工(V5.153 汇总整合):
 *   ① 本表 ResourceKnowledge —— 静态公知: 每种资源的最佳 Y / biome / 找法(SURFACE/DIG/NETHER)。
 *   ② BiomePrior —— 横向方向: 当前/目标点 biome 对某资源的亲和度,驱动 setExplore 朝资源多的方向走。
 *   ③ SharedResourceMap —— 动态情报: 别的假人实际在哪挖到过(模糊坐标+认领),「具体去哪」。
 *   「找资源」决策顺序应是: 先查 ③(有具体坐标直奔) → 落 ①②(没情报则按公知朝对的方向/深度找)。
 *   未来扩展(铜/金/红石进入进度链时): 各资源已有 Y+找法+地标桥,新阶段直接查表,无需再各写一套。
 */
public final class ResourceKnowledge {

    private ResourceKnowledge() {}

    /** 找法:地表搜寻 / 向下挖到目标层 / 下界远征。 */
    public enum Strategy { SURFACE, DIG, NETHER }

    /**
     * 资源公知条目。digTargetY = 该资源「向下挖」时的最佳目标层(overworld);
     * SURFACE 资源该值为名义地表高度(仅作参考,不驱动下挖)。
     * biomeHint = 横向探索时该资源对应的 BiomePrior 亲和类型(未接入 BiomePrior 的资源为 null)。
     */
    public enum Resource {
        WOOD     (Strategy.SURFACE,  64, BiomePrior.ResourceType.LOG),
        STONE    (Strategy.DIG,       0, BiomePrior.ResourceType.STONE),
        COAL     (Strategy.DIG,      15, BiomePrior.ResourceType.COAL),  // 顺路捡,不爬 Y128
        COPPER   (Strategy.DIG,      48, null),
        IRON     (Strategy.DIG,      15, BiomePrior.ResourceType.IRON),
        GOLD     (Strategy.DIG,     -16, null),
        REDSTONE (Strategy.DIG,     -59, null),
        LAPIS    (Strategy.DIG,      -1, null),
        DIAMOND  (Strategy.DIG,     -54, null),  // 表 -59;取 -54 避 StripMine 底岩守卫(Y≤-56 abort)
        NETHERITE(Strategy.NETHER,   15, null);

        public final Strategy strategy;
        public final int digTargetY;
        public final BiomePrior.ResourceType biomeHint;

        Resource(Strategy strategy, int digTargetY, BiomePrior.ResourceType biomeHint) {
            this.strategy = strategy;
            this.digTargetY = digTargetY;
            this.biomeHint = biomeHint;
        }

        /**
         * V5.153 (汇总整合): 该资源对应的共享地图地标类型 —— 把静态公知桥接到 {@link SharedResourceMap}
         *   的「假人间共享:具体在哪」动态情报。无对应类型(铜/金/红石/青金石/下界合金暂无共享白名单)返 null。
         *   三层认知分工: 本表(静态:最佳 Y+biome+找法) → BiomePrior(横向:朝哪个 biome) →
         *   SharedResourceMap(动态:别的假人在哪挖到过)。「找资源」决策应先查动态(具体坐标)、再落静态(方向/深度)。
         */
        public SharedResourceMap.LandmarkType sharedLandmark() {
            switch (this) {
                case IRON:  return SharedResourceMap.LandmarkType.IRON_DEPOSIT;
                case COAL:  return SharedResourceMap.LandmarkType.COAL_VEIN;
                case WOOD:  return SharedResourceMap.LandmarkType.LOG_CLUSTER;
                case STONE: return SharedResourceMap.LandmarkType.STONE_AREA;
                default:    return null;
            }
        }
    }

    /**
     * strip-mine 当前目标的最佳挖掘层 Y —— StripMineBehavior DESCEND 的目标高度单一入口。
     *   config 优先(用户可在 maohi.properties 调 stripMineTargetY / stripMineDiamondTargetY),
     *   config 缺失时回落本公知表(IRON.digTargetY / DIAMOND.digTargetY)。
     *   圆石目标(stripMineForCobble)与铁目标同走铁层 —— 石头全地下皆有、且圆石在 DESCEND 早退,
     *   深度不关键,复用铁层即可(行为与改造前一致)。
     */
    public static int stripMineTargetY(Personality pers, MaohiConfig cfg) {
        boolean forDiamond = pers != null && pers.stripMineForDiamond;
        if (forDiamond) {
            return cfg != null ? cfg.stripMineDiamondTargetY : Resource.DIAMOND.digTargetY;
        }
        return cfg != null ? cfg.stripMineTargetY : Resource.IRON.digTargetY;
    }

    /**
     * V5.153 (汇总整合 + 增量 A): 当前阶段「地表探索」时最该朝哪种资源的 biome 走。
     *   统一此前散在 PhaseUtil.setExplore / hostileEscapeYaw 两处逐字重复的 phase→resource 映射(单一事实源)。
     *   关键改动: IRON_AGE 由原来死偏 LOG(森林)改偏 IRON(山地)—— 铁器期假人地表 explore 应朝
     *   「露天铁/洞穴多」的山地走(BiomePrior.ironAffinity: 山地+2/恶地+1),而非一味找森林。DIAMOND/NETHER/
     *   ENDER 仍偏 LOG(深层资源靠下挖、地表主要补木料柄/燃料,保守不动,避免回归)。
     */
    public static BiomePrior.ResourceType surfaceExploreBias(GrowthPhase phase, boolean hasPickaxe) {
        if (phase == GrowthPhase.WOOD_AGE) {
            return BiomePrior.ResourceType.LOG;
        }
        if (phase == GrowthPhase.STONE_AGE) {
            // 无镐先找石头合镐(STONE),有镐转找木(后续合木棍/修工具),与改造前完全一致。
            return hasPickaxe ? BiomePrior.ResourceType.LOG : BiomePrior.ResourceType.STONE;
        }
        if (phase == GrowthPhase.IRON_AGE) {
            return BiomePrior.ResourceType.IRON;  // V5.153: 偏山地找露天铁/洞穴(原死偏 LOG)
        }
        return BiomePrior.ResourceType.LOG;       // DIAMOND/NETHER/ENDER: 保守偏森林(地表主要补木料)
    }

    /**
     * V5.153 (增量 B): 假人当前是否「明显在某 DIG 资源的最佳层之上」—— 用于把「地表想挖矿却没探到矿」
     *   的横向瞎逛改成「朝公知层下挖」。margin 给足(默认 +10)避免在层附近反复横纵抖动。
     *   SURFACE/NETHER 资源恒返 false(不靠下挖)。
     */
    public static boolean isWellAboveLayer(Resource res, int botY) {
        if (res == null || res.strategy != Strategy.DIG) return false;
        return botY > res.digTargetY + 10;
    }
}
