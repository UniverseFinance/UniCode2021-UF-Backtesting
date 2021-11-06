package finance.universe.backtest.service;

import com.google.common.collect.Lists;
import finance.universe.backtest.entity.HourKline;
import finance.universe.backtest.entity.Pool;
import finance.universe.backtest.entity.SwapRecord;
import finance.universe.backtest.repository.HourKlineRepository;
import finance.universe.backtest.repository.PoolRepository;
import finance.universe.backtest.repository.SwapRecordRepository;
import finance.universe.backtest.util.BaseUtil;
import finance.universe.backtest.vo.BackTestTickParams;
import finance.universe.backtest.vo.Direction;
import finance.universe.backtest.vo.Rebalance;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.web3j.tuples.generated.Tuple2;
import org.web3j.tuples.generated.Tuple3;
import org.web3j.tuples.generated.Tuple7;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import static finance.universe.backtest.util.BaseUtil.safeDivide;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
@Slf4j
@Service
public class BackTestService {

    @Autowired
    PoolRepository poolRepository;
    @Autowired
    HourKlineRepository klineRepository;
    @Autowired
    SwapRecordRepository swapRecordRepository;

    private int nonce = 0;

    private final BigInteger FixedPoint96_Q96 = new BigInteger("1000000000000000000000000", 16);
    private final BigInteger avgRebalanceGasUseed = BigInteger.valueOf(400000);

    // mock data
    @Data
    public static class ImData {
        private long ts;
        private BigDecimal im;

        public ImData(long ts, BigDecimal im) {
            this.ts = ts;
            this.im = im;
        }
    }

    /**
     * back test
     * @param params
     * @return
     * @throws Exception
     */
    public Map<String, Object> doBackTest(BackTestTickParams params) throws Exception {
        long taskStartTime = System.currentTimeMillis();
        Pool poolInfo = poolRepository.findById(params.getPair()).orElse(null);
        if (poolInfo == null) {
            throw new Exception("pool miss");
        }

        Long maxTs = klineRepository.getMaxTsByPair(params.getPair());
        if (maxTs == null) {
            throw new Exception("swap miss");
        }

        long endTs = params.getEndTs() == null ? maxTs : params.getEndTs();
        long startTs = params.getStartTs();
        long days = (endTs - startTs) / (3600 * 24);

        long taskStagTime = System.currentTimeMillis();

        List<HourKline> klineVoList = klineRepository.findByPairAndTsLessThanEqualOrderByTs(params.getPair(), endTs);
        if (klineVoList == null || klineVoList.isEmpty()) {
            throw new Exception("kline miss");
        }

        log.info("doTask stag1 get kline, consume: {}", System.currentTimeMillis() - taskStagTime);

        log.info("doTask stag2 get trend ind, consume: {}", System.currentTimeMillis() - taskStartTime);
        taskStagTime = System.currentTimeMillis();

        List<SwapRecord> swapRecordVoList = swapRecordRepository.findByPairAndTsGreaterThanEqualAndTsLessThanEqual(params.getPair(), startTs, endTs);
        if (swapRecordVoList == null || swapRecordVoList.isEmpty()) {
            throw new Exception("swap miss");
        }

        log.info("doTask stag3 get swaps, consume: {}", System.currentTimeMillis() - taskStagTime);
        taskStagTime = System.currentTimeMillis();

        Map<Long, HourKline> klineVoMap = klineVoList.stream().collect(Collectors.toMap(HourKline::getTs, it -> it));

        Map<Long, List<SwapRecord>> swapMap = swapRecordVoList.stream().collect(Collectors.groupingBy(SwapRecord::getBlockNumber));
        List<SwapRecord> swapBlockedList = new ArrayList<>();
        swapMap.forEach((k, v) -> {
            SwapRecord last = v.get(v.size() - 1);
            SwapRecord swapRecordVo = new SwapRecord();
            swapRecordVo.setAmount0(v.stream().map(it -> it.getAmount0().compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : it.getAmount0()).reduce(BigDecimal.ZERO, BigDecimal::add));
            swapRecordVo.setAmount1(v.stream().map(it -> it.getAmount1().compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : it.getAmount1()).reduce(BigDecimal.ZERO, BigDecimal::add));
            swapRecordVo.setTick(last.getTick());
            swapRecordVo.setTs(last.getTs());
            swapRecordVo.setBlockNumber(k);
            swapRecordVo.setPrice(BaseUtil.getPriceByTick(poolInfo, BigInteger.valueOf(last.getTick())));
            if (v.size() > 1) {
                swapRecordVo.setGasPrice(getMedium(v.stream().map(SwapRecord::getGasPrice).collect(Collectors.toList())));
            } else {
                swapRecordVo.setGasPrice(last.getGasPrice());
            }
            BigDecimal liquidity = getTotalLiquidFromKline(last.getTs(), klineVoMap);
            if (liquidity != null) {
                swapRecordVo.setLiquidity(liquidity);
                swapBlockedList.add(swapRecordVo);
            }
        });

        // order by block
        swapBlockedList.sort(Comparator.comparing(SwapRecord::getBlockNumber));

        log.info("doTask stag4 get blocked records, consume: {}", System.currentTimeMillis() - taskStagTime);
        taskStagTime = System.currentTimeMillis();

        BigDecimal startPrice = swapBlockedList.get(0).getPrice();
        BigDecimal endPrice = swapBlockedList.get(swapBlockedList.size() - 1).getPrice();
        BigDecimal highPrice = swapBlockedList.stream().map(SwapRecord::getPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal lowPrice = swapBlockedList.stream().map(SwapRecord::getPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        long boundaryThreshold = BaseUtil.floor(params.getBoundaryThreshold(), poolInfo.getTickSpacing());

        long ts = startTs;
        BigDecimal price = startPrice;
        long tick = swapBlockedList.get(0).getTick();
        BigInteger tickPrice = BigDecimal.valueOf(Math.pow(1.0001, tick)).toBigInteger();

        long middleTick = BaseUtil.floor(tick, poolInfo.getTickSpacing());
        long lowerTick = middleTick - boundaryThreshold;
        long upperTick = middleTick + boundaryThreshold;

        BigDecimal startNetValue = BaseUtil.getNetValue(poolInfo, params.getAmount0(), params.getAmount1(), startPrice);

        BigDecimal endNetValue = BigDecimal.ZERO;

        List<ImData> rateList = new ArrayList<>();
        rateList.add(new ImData(startTs, BigDecimal.ONE));

        //rebalance signal
        boolean reBalanceSignal = false;
        int reBalanceDirection = -1;

        int reU = 0;
        int reD = 0;
        int reWin = 0;

        // fee
        BigInteger totalCommission0 = BigInteger.ZERO;
        BigInteger totalCommission1 = BigInteger.ZERO;
        BigInteger tempCommission0 = BigInteger.ZERO;
        BigInteger tempCommission1 = BigInteger.ZERO;

        // sqrtPriceX96
        BigInteger sqrtPrice = BaseUtil.getSqrtPriceByTick(tick);
        BigInteger sqrtLower = BaseUtil.getSqrtPriceByTick(lowerTick);
        BigInteger sqrtUpper = BaseUtil.getSqrtPriceByTick(upperTick);

        // first staking liquidity
        Tuple7<BigInteger, BigInteger, BigInteger, BigInteger, BigInteger, BigInteger, Integer> tp7 = addLiquidity(poolInfo, sqrtPrice, sqrtLower, sqrtUpper, tickPrice, BaseUtil.getAmountByScale(params.getAmount0(), poolInfo.getDecimal0()), BaseUtil.getAmountByScale(params.getAmount1(), poolInfo.getDecimal1()), poolInfo.getSwapFee());
        BigInteger liquidity = tp7.component1();
        BigInteger amount0 = tp7.component2();
        BigInteger amount1 = tp7.component3();
        BigInteger swapFee = tp7.component4();
        BigInteger change0 = tp7.component5();
        BigInteger change1 = tp7.component6();
        Integer tokenId = tp7.component7();

        BigInteger start0 = amount0.add(change0);
        BigInteger start1 = amount1.add(change1);

        BigInteger swapFee0 = BigInteger.ZERO;
        BigInteger swapFee1 = BigInteger.ZERO;
        if (tokenId == 0) {
            swapFee0 = swapFee0.add(swapFee);
        } else {
            swapFee1 = swapFee1.add(swapFee);
        }

        List<List<Object>> eventList = new ArrayList<>();
        List<Object> paramList = new ArrayList<>();
        paramList.add(String.valueOf(ts));
        paramList.add(price.toPlainString());
        paramList.add(BaseUtil.getAmountWithScale(amount0, poolInfo.getDecimal0()).toPlainString());
        paramList.add(BaseUtil.getAmountWithScale(amount1, poolInfo.getDecimal1()).toPlainString());
        paramList.add(liquidity.toString());
        paramList.add(BaseUtil.getAmountWithScale(change0, poolInfo.getDecimal0()).toPlainString());
        paramList.add(BaseUtil.getAmountWithScale(change1, poolInfo.getDecimal1()).toPlainString());
        paramList.add("0");
        paramList.add("0");
        paramList.add("50000000000");
        paramList.add("300000");
        paramList.add("add");
        eventList.add(paramList);

        log.info("doTask stag5 init liquid, consume: {}", System.currentTimeMillis() - taskStagTime);
        taskStagTime = System.currentTimeMillis();

        Long lastRebalanceTime = swapBlockedList.get(0).getTs();

        Map<Long, Rebalance> rebalanceMap = new HashMap<>();
        List<Rebalance> rebalanceList = params.getRebalance();
        if (!CollectionUtils.isEmpty(rebalanceList)) {
            for (Rebalance rebalance : rebalanceList) {
//                if (rebalance.getBlock() >= lastRebalanceTime) {
//                    rebalanceMap.put(rebalance.getBlock(), rebalance);
//                }
                rebalanceMap.put(rebalance.getBlock(), rebalance);
            }
        }

        for (SwapRecord swapRecordVo : swapBlockedList) {
            ts = swapRecordVo.getTs();
            price = swapRecordVo.getPrice();
            tick = swapRecordVo.getTick();
            tickPrice = BigDecimal.valueOf(Math.pow(1.0001, tick)).toBigInteger();
            sqrtPrice = BaseUtil.getSqrtPriceByTick(tick);
            BigDecimal gasPrice = swapRecordVo.getGasPrice();

            Rebalance rebalance = rebalanceMap.get(swapRecordVo.getBlockNumber());

            if (sqrtPrice.compareTo(sqrtLower) >= 0 && sqrtPrice.compareTo(sqrtUpper) <= 0) {
                BigDecimal totalLiquidity = swapRecordVo.getLiquidity();
                BigDecimal ratio = BaseUtil.safeDivide(new BigDecimal(liquidity), totalLiquidity);
                BigDecimal t0 = swapRecordVo.getAmount0().multiply(poolInfo.getSwapFee()).multiply(ratio);
                tempCommission0 = tempCommission0.add(BaseUtil.getAmountByScale(t0, poolInfo.getDecimal0()));
                BigDecimal t1 = swapRecordVo.getAmount1().multiply(poolInfo.getSwapFee()).multiply(ratio);
                tempCommission1 = tempCommission1.add(BaseUtil.getAmountByScale(t1, poolInfo.getDecimal1()));
            }

            Tuple2<BigDecimal, BigDecimal> tp2 = getNetValueAndIm(poolInfo, sqrtPrice, sqrtLower, sqrtUpper, liquidity, price, start0, start1, change0, change1, tempCommission0, tempCommission1);
            endNetValue = tp2.component1();
            rateList.add(new ImData(ts, tp2.component2()));
            boolean forceRebalance = Objects.nonNull(rebalance) && rebalance.getUpper() > rebalance.getLower();
            if (reBalanceSignal || forceRebalance) {
                if (Math.abs(tick - middleTick) >= params.getReBalanceThreshold() && BaseUtil.toGWei(swapRecordVo.getGasPrice().toBigInteger()).compareTo(BigInteger.valueOf(200)) <= 0 || forceRebalance) {
                    Tuple3<BigInteger, BigInteger, BigDecimal> removeResult = removeLiquidity(sqrtPrice, sqrtLower, sqrtUpper, liquidity, tickPrice, amount0, amount1);
                    BigInteger remove0 = removeResult.component1();
                    BigInteger remove1 = removeResult.component2();
                    BigDecimal im = removeResult.component3();

                    BigDecimal cp = BaseUtil.getCommissionRate(amount0, amount1, tempCommission0, tempCommission1, tickPrice);
                    totalCommission0 = totalCommission0.add(tempCommission0);
                    totalCommission1 = totalCommission1.add(tempCommission1);

                    if (im.add(cp).compareTo(BigDecimal.ZERO) > 0) {
                        reWin++;
                    }

                    paramList = new ArrayList<>();
                    paramList.add(String.valueOf(ts));
                    paramList.add(price.toPlainString());
                    paramList.add(BaseUtil.getAmountWithScale(remove0.negate(), poolInfo.getDecimal0()).toPlainString());
                    paramList.add(BaseUtil.getAmountWithScale(remove1.negate(), poolInfo.getDecimal1()).toPlainString());
                    paramList.add(liquidity.negate().toString());
                    paramList.add(BaseUtil.getAmountWithScale(amount0, poolInfo.getDecimal0()).toPlainString());
                    paramList.add(BaseUtil.getAmountWithScale(amount1, poolInfo.getDecimal1()).toPlainString());
                    paramList.add(im.toPlainString());
                    paramList.add(cp.toPlainString());
                    paramList.add(gasPrice.stripTrailingZeros().toPlainString());
                    paramList.add("0");
                    paramList.add("remove");
                    eventList.add(paramList);

                    log.info("rebalance, startTs: {}, endTs: {}, lowerTick: {}, upperTick: {}, liquidity: {}, amount0: {}, amount1: {}, stop0: {}, stop1: {}, fee0: {}, fee1: {}, cp: {}, im: {}, gasFee: {}",
                            new Timestamp(lastRebalanceTime * 1000), new Timestamp(swapRecordVo.getTs() * 1000), lowerTick, upperTick, liquidity, amount0, amount1, remove0, remove1, tempCommission0, tempCommission1, cp, im, BaseUtil.toEther(gasPrice.multiply(new BigDecimal(avgRebalanceGasUseed)).toBigInteger()));

                    amount0 = remove0.add(change0).add(tempCommission0);
                    amount1 = remove1.add(change1).add(tempCommission1);
                    tempCommission0 = BigInteger.ZERO;
                    tempCommission1 = BigInteger.ZERO;

                    lastRebalanceTime = swapRecordVo.getTs();

                    middleTick = BaseUtil.floor(tick, poolInfo.getTickSpacing());
                    lowerTick = middleTick - boundaryThreshold;
                    upperTick = middleTick + boundaryThreshold;
                    if (Objects.nonNull(rebalance) && rebalance.getLower() < rebalance.getUpper()) {
                        lowerTick = rebalance.getLower();
                        upperTick = rebalance.getUpper();
                    }

                    sqrtLower = BaseUtil.getSqrtPriceByTick(lowerTick);
                    sqrtUpper = BaseUtil.getSqrtPriceByTick(upperTick);

                    tp7 = addLiquidity(poolInfo, sqrtPrice, sqrtLower, sqrtUpper, tickPrice, amount0, amount1, poolInfo.getSwapFee());
                    liquidity = tp7.component1();
                    amount0 = tp7.component2();
                    amount1 = tp7.component3();
                    swapFee = tp7.component4();
                    change0 = tp7.component5();
                    change1 = tp7.component6();
                    tokenId = tp7.component7();

                    if (tokenId == 0) {
                        swapFee0 = swapFee0.add(swapFee);
                    } else {
                        swapFee1 = swapFee1.add(swapFee);
                    }

                    paramList = new ArrayList<>();
                    paramList.add(String.valueOf(ts));
                    paramList.add(price.toPlainString());
                    paramList.add(BaseUtil.getAmountWithScale(amount0, poolInfo.getDecimal0()).toPlainString());
                    paramList.add(BaseUtil.getAmountWithScale(amount1, poolInfo.getDecimal1()).toPlainString());
                    paramList.add(liquidity.toString());
                    paramList.add(BaseUtil.getAmountWithScale(change0, poolInfo.getDecimal0()).toPlainString());
                    paramList.add(BaseUtil.getAmountWithScale(change1, poolInfo.getDecimal1()).toPlainString());
                    paramList.add("0");
                    paramList.add("0");
                    paramList.add(gasPrice.stripTrailingZeros().toPlainString());
                    paramList.add(avgRebalanceGasUseed.toString());
                    paramList.add("add");
                    eventList.add(paramList);

                    if (reBalanceDirection == 0) {
                        reU++;
                    } else {
                        reD++;
                    }
                }
                reBalanceSignal = false;
            }

        }

        log.info("doTask stag6 run loop for swaps, consume: {}", System.currentTimeMillis() - taskStagTime);
        taskStagTime = System.currentTimeMillis();

        BigDecimal cp = BaseUtil.getCommissionRate(amount0, amount1, tempCommission0, tempCommission1, tickPrice);
        totalCommission0 = totalCommission0.add(tempCommission0);
        totalCommission1 = totalCommission1.add(tempCommission1);

        Tuple3<BigInteger, BigInteger, BigDecimal> removeResult = removeLiquidity(sqrtPrice, sqrtLower, sqrtUpper, liquidity, tickPrice, amount0, amount1);
        BigInteger remove0 = removeResult.component1();
        BigInteger remove1 = removeResult.component2();
        BigDecimal im = removeResult.component3();
        amount0 = remove0.add(change0).add(tempCommission0);
        amount1 = remove1.add(change1).add(tempCommission1);

        paramList = new ArrayList<>();
        paramList.add(String.valueOf(ts));
        paramList.add(price.toPlainString());
        paramList.add(BaseUtil.getAmountWithScale(remove0.negate(), poolInfo.getDecimal0()).toPlainString());
        paramList.add(BaseUtil.getAmountWithScale(remove1.negate(), poolInfo.getDecimal1()).toPlainString());
        paramList.add(liquidity.negate().toString());
        paramList.add(BaseUtil.getAmountWithScale(amount0, poolInfo.getDecimal0()).toPlainString());
        paramList.add(BaseUtil.getAmountWithScale(amount1, poolInfo.getDecimal1()).toPlainString());
        paramList.add(im.toPlainString());
        paramList.add(cp.toPlainString());
        paramList.add("50000000000");
        paramList.add("200000");
        paramList.add("remove");
        eventList.add(paramList);

        if (im.add(cp).compareTo(BigDecimal.ZERO) > 0) {
            reWin++;
        }

        log.info("doTask stag7 make result 1, consume: {}", System.currentTimeMillis() - taskStagTime);
        taskStagTime = System.currentTimeMillis();

        rateList.forEach(it -> {
            it.setTs(BaseUtil.alignedToHour(it.getTs() * 1000) / 1000);
        });
        Map<Long, List<ImData>> imMap = rateList.stream().collect(Collectors.groupingBy(ImData::getTs));
        List<ImData> imAlignedList = new ArrayList<>();
        imMap.forEach((k, v) -> {
            imAlignedList.add(new ImData(k, v.get(v.size() - 1).getIm()));
        });
        imAlignedList.sort(Comparator.comparing(ImData::getTs));

        log.info("doTask stag7 make result 2, consume: {}", System.currentTimeMillis() - taskStagTime);
        taskStagTime = System.currentTimeMillis();

        BigDecimal uRate = safeDivide(endNetValue.subtract(startNetValue), startNetValue);
        BigDecimal startB = safeDivide(startNetValue, startPrice);
        BigDecimal endB = safeDivide(endNetValue, endPrice);
        BigDecimal bRate = safeDivide(endB.subtract(startB), startB);

        BigDecimal cuRate, realRate;
        BigDecimal t0 = BaseUtil.getAmountWithScale(totalCommission0, poolInfo.getDecimal0());
        BigDecimal t1 = BaseUtil.getAmountWithScale(totalCommission1, poolInfo.getDecimal1());
        BigDecimal s0 = BaseUtil.getAmountWithScale(start0, poolInfo.getDecimal0());
        BigDecimal s1 = BaseUtil.getAmountWithScale(start1, poolInfo.getDecimal1());
        if (poolInfo.testReverse()) {
            cuRate = safeDivide(t0.add(t1.multiply(price)), startNetValue);
            realRate = safeDivide(endNetValue, s0.add(s1.multiply(price))).subtract(BigDecimal.ONE);
        } else {
            cuRate = safeDivide(t1.add(t0.multiply(price)), startNetValue);
            realRate = safeDivide(endNetValue, s1.add(s0.multiply(price))).subtract(BigDecimal.ONE);
        }

        BigDecimal uAPR = safeDivide(uRate.multiply(BigDecimal.valueOf(365)), BigDecimal.valueOf(days));
        BigDecimal bAPR = safeDivide(bRate.multiply(BigDecimal.valueOf(365)), BigDecimal.valueOf(days));
        BigDecimal realAPR = safeDivide(realRate.multiply(BigDecimal.valueOf(365)), BigDecimal.valueOf(days));
        BigDecimal cuAPR = safeDivide(cuRate.multiply(BigDecimal.valueOf(365)), BigDecimal.valueOf(days));

        log.info("doTask stag7 make result 3, consume: {}", System.currentTimeMillis() - taskStagTime);
        taskStagTime = System.currentTimeMillis();

        BigDecimal maxDrawDown = getMaxDrawDown(rateList.stream().map(ImData::getIm).collect(Collectors.toList()));
        BigDecimal vol = getVol(imAlignedList.stream().map(ImData::getIm).collect(Collectors.toList()));
        BigDecimal sharpe = safeDivide(realAPR, vol);
        BigDecimal winRate = BigDecimal.ZERO;
        if (reU + reD != 0) {
            winRate = BigDecimal.valueOf((double) reWin / (reU + reD + 1));
        }

        log.info("doTask stag7 make result 4, consume: {}", System.currentTimeMillis() - taskStagTime);
        taskStagTime = System.currentTimeMillis();

        Map<String, Object> resultMap = new HashMap<>();
        String reportName = String.format("%s_%d_%d", params.getPair(), System.currentTimeMillis() / 1000, nonce);
        resultMap.put("report_name", reportName);
        nonce++;

        Map<String, Object> baseInfoMap = new HashMap<>();
        baseInfoMap.put("start_ts", String.valueOf(params.getStartTs()));
        baseInfoMap.put("end_ts", String.valueOf(params.getEndTs()));
        baseInfoMap.put("lower_rate", params.getBoundaryThreshold().toString());
        baseInfoMap.put("upper_rate", params.getBoundaryThreshold().toString());
        baseInfoMap.put("reb_rate", params.getReBalanceThreshold().toString());
        baseInfoMap.put("tier", poolInfo.getSwapFee().stripTrailingZeros().toPlainString());
        baseInfoMap.put("token0", poolInfo.getToken0());
        baseInfoMap.put("token1", poolInfo.getToken1());
        baseInfoMap.put("decimal0", String.valueOf(poolInfo.getDecimal0()));
        baseInfoMap.put("decimal1", String.valueOf(poolInfo.getDecimal1()));
        resultMap.put("base_info", baseInfoMap);

        resultMap.put("ts_list", imAlignedList.stream().map(it -> String.valueOf(it.getTs())).collect(Collectors.toList()));
        resultMap.put("im_list", imAlignedList.stream().map(it -> it.getIm().toPlainString()).collect(Collectors.toList()));
        resultMap.put("trade_info", eventList);

        Map<String, Object> marketInfoMap = new HashMap<>();
        marketInfoMap.put("open", startPrice.toPlainString());
        marketInfoMap.put("close", endPrice.toPlainString());
        marketInfoMap.put("high", highPrice.toPlainString());
        marketInfoMap.put("low", lowPrice.toPlainString());
        resultMap.put("market_info", marketInfoMap);

        BigDecimal sw0 = BaseUtil.getAmountWithScale(swapFee0, poolInfo.getDecimal0());
        BigDecimal sw1 = BaseUtil.getAmountWithScale(swapFee1, poolInfo.getDecimal1());

        Map<String, Object> globalInfoMap = new HashMap<>();
        globalInfoMap.put("commission", Lists.newArrayList(t0.toPlainString(), t1.toPlainString()));
        globalInfoMap.put("swapFee", Lists.newArrayList(sw0, sw1));
        globalInfoMap.put("reBalanceTime", Lists.newArrayList(reU, reD));
        globalInfoMap.put("rate", Lists.newArrayList(realRate.toPlainString(), cuRate.toPlainString(), uRate.toPlainString(), bRate.toPlainString()));
        globalInfoMap.put("apr", Lists.newArrayList(realAPR.toPlainString(), cuAPR.toPlainString(), uAPR.toPlainString(), bAPR.toPlainString()));
        resultMap.put("global_info", globalInfoMap);

        Map<String, Object> riskInfoMap = new HashMap<>();
        riskInfoMap.put("maxDrawDown", maxDrawDown.toPlainString());
        riskInfoMap.put("volatility", vol.toPlainString());
        riskInfoMap.put("sharpe", sharpe.toPlainString());
        riskInfoMap.put("winRate", winRate.toPlainString());
        resultMap.put("risk_info", riskInfoMap);
        log.info("doTask stag7 make result 5, consume: {}", System.currentTimeMillis() - taskStagTime);
        log.info("doTask end, consume: {}", System.currentTimeMillis() - taskStartTime);
        return resultMap;
    }

    private BigDecimal getMedium(List<BigDecimal> list) {
        list.sort(BigDecimal::compareTo);
        if (list.size() % 2 == 0) {
            // 4/2 = 2
            int mid = list.size() / 2;
            BigDecimal a = list.get(mid - 1);
            BigDecimal b = list.get(mid);
            return BaseUtil.safeDivide(a.add(b), BigDecimal.valueOf(2));
        } else {
            // 3/2 = 1
            return list.get(list.size() / 2);
        }
    }

    private BigDecimal getMaxDrawDown(List<BigDecimal> list) {
        BigDecimal maxDrawBack = BigDecimal.ZERO;
        BigDecimal peek = BigDecimal.ZERO;
        BigDecimal min = BigDecimal.ZERO;
        for (BigDecimal k : list) {
            if (k.compareTo(peek) >= 0) {
                if (peek.compareTo(min) > 0) {
                    BigDecimal drawBack = BaseUtil.safeDivide(peek.subtract(min), peek);
                    if (drawBack.compareTo(maxDrawBack) > 0) {
                        maxDrawBack = drawBack;
                    }
                }
                peek = k;
                min = k;
            } else if (k.compareTo(min) < 0) {
                min = k;
            }
        }
        BigDecimal drawBack = BaseUtil.safeDivide(peek.subtract(min), peek);
        if (drawBack.compareTo(maxDrawBack) > 0) {
            maxDrawBack = drawBack;
        }
        return maxDrawBack;
    }

    // calculate volatility
    private BigDecimal getVol(List<BigDecimal> imList) {
        List<BigDecimal> portfolioReturnList = new ArrayList<>();
        for (int i = 1; i < imList.size(); i++) {
            portfolioReturnList.add(safeDivide(imList.get(i), imList.get(i - 1)).subtract(BigDecimal.ONE));
        }
        BigDecimal sum = portfolioReturnList.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = safeDivide(sum, BigDecimal.valueOf(portfolioReturnList.size()));
        BigDecimal ret = BigDecimal.ZERO;
        for (BigDecimal r : portfolioReturnList) {
            ret = ret.add(r.subtract(mean).pow(2));
        }
        BigDecimal var = safeDivide(ret, BigDecimal.valueOf(portfolioReturnList.size()));
        return BigDecimal.valueOf(Math.sqrt(var.multiply(BigDecimal.valueOf(24 * 365)).doubleValue()));
    }


    private Tuple2<BigDecimal, BigDecimal> getNetValueAndIm(Pool poolInfo, BigInteger sqrtPrice, BigInteger sqrtLower, BigInteger sqrtUpper, BigInteger liquidity, BigDecimal price, BigInteger old0, BigInteger old1, BigInteger change0, BigInteger change1, BigInteger temp0, BigInteger temp1) {
        Tuple2<BigInteger, BigInteger> tp2 = getAmountsForLiquidity(sqrtPrice, sqrtLower, sqrtUpper, liquidity);
        BigInteger amount0 = tp2.component1();
        BigInteger amount1 = tp2.component2();
        BigInteger new0 = amount0.add(change0).add(temp0);
        BigInteger new1 = amount1.add(change1).add(temp1);

        BigDecimal newWithScale0 = BaseUtil.getAmountWithScale(new0, poolInfo.getDecimal0());
        BigDecimal newWithScale1 = BaseUtil.getAmountWithScale(new1, poolInfo.getDecimal1());
        BigDecimal oldWithScale0 = BaseUtil.getAmountWithScale(old0, poolInfo.getDecimal0());
        BigDecimal oldWithScale1 = BaseUtil.getAmountWithScale(old1, poolInfo.getDecimal1());

        BigDecimal nv, im;
        if (poolInfo.testReverse()) {
            nv = newWithScale0.add(newWithScale1.multiply(price));
            im = safeDivide(nv, oldWithScale0.add(oldWithScale1.multiply(price)));
        } else {
            nv = newWithScale1.add(newWithScale0.multiply(price));
            im = safeDivide(nv, oldWithScale1.add(oldWithScale0.multiply(price)));
        }
        return new Tuple2<>(nv, im);
    }

    private BigDecimal getTotalLiquidFromKline(long ts, Map<Long, HourKline> klineVoMap) {
        long tsAligned = BaseUtil.alignedToHour(ts * 1000);
        HourKline klineVo = klineVoMap.get(tsAligned / 1000);
        return klineVo != null ? klineVo.getLiquidity() : null;
    }

    public BigInteger mulDiv(BigInteger a, BigInteger b, BigInteger denominator) {
        return a.multiply(b).divide(denominator);
    }

    public BigInteger getAmount0ForLiquidity(BigInteger sqrtLower, BigInteger sqrtUpper, BigInteger liquidity) {
        if (sqrtLower.compareTo(sqrtUpper) > 0) {
            BigInteger temp = sqrtUpper;
            sqrtUpper = sqrtLower;
            sqrtLower = temp;
        }

        return mulDiv(liquidity.multiply(FixedPoint96_Q96), sqrtUpper.subtract(sqrtLower), sqrtUpper).divide(sqrtLower);
    }

    public BigInteger getAmount1ForLiquidity(BigInteger sqrtLower, BigInteger sqrtUpper, BigInteger liquidity) {
        if (sqrtLower.compareTo(sqrtUpper) > 0) {
            BigInteger temp = sqrtUpper;
            sqrtUpper = sqrtLower;
            sqrtLower = temp;
        }

        // FullMath.mulDiv(liquidity, sqrtRatioBX96 - sqrtRatioAX96, FixedPoint96.Q96);
        return mulDiv(liquidity, sqrtUpper.subtract(sqrtLower), FixedPoint96_Q96);
    }

    public Tuple2<BigInteger, BigInteger> getAmountsForLiquidity(BigInteger sqrtPrice, BigInteger sqrtLower, BigInteger sqrtUpper, BigInteger liquidity) {
        BigInteger amount0, amount1;
        if (sqrtLower.compareTo(sqrtUpper) > 0) {
            BigInteger temp = sqrtUpper;
            sqrtUpper = sqrtLower;
            sqrtLower = temp;
        }

        if (sqrtPrice.compareTo(sqrtLower) <= 0) {
            amount0 = getAmount0ForLiquidity(sqrtLower, sqrtUpper, liquidity);
            amount1 = BigInteger.ZERO;
        } else if (sqrtPrice.compareTo(sqrtUpper) <= 0) {
            amount0 = getAmount0ForLiquidity(sqrtPrice, sqrtUpper, liquidity);
            amount1 = getAmount1ForLiquidity(sqrtLower, sqrtPrice, liquidity);
        } else {
            amount0 = BigInteger.ZERO;
            amount1 = getAmount1ForLiquidity(sqrtLower, sqrtUpper, liquidity);
        }
        return new Tuple2<>(amount0, amount1);
    }

    public BigInteger getLiquidityForAmount0(BigInteger sqrtLower, BigInteger sqrtUpper, BigInteger amount0) {
        if (sqrtLower.compareTo(sqrtUpper) > 0) {
            BigInteger temp = sqrtUpper;
            sqrtUpper = sqrtLower;
            sqrtLower = temp;
        }
        // uint256 intermediate = FullMath.mulDiv(sqrtRatioAX96, sqrtRatioBX96, FixedPoint96.Q96);
        BigInteger intermediate = mulDiv(sqrtLower, sqrtUpper, FixedPoint96_Q96);
        // toUint128(FullMath.mulDiv(amount0, intermediate, sqrtRatioBX96 - sqrtRatioAX96))
        return mulDiv(amount0, intermediate, sqrtUpper.subtract(sqrtLower));
    }

    public BigInteger getLiquidityForAmount1(BigInteger sqrtLower, BigInteger sqrtUpper, BigInteger amount1) {
        if (sqrtLower.compareTo(sqrtUpper) > 0) {
            BigInteger temp = sqrtUpper;
            sqrtUpper = sqrtLower;
            sqrtLower = temp;
        }
        // toUint128(FullMath.mulDiv(amount1, FixedPoint96.Q96, sqrtRatioBX96 - sqrtRatioAX96))
        return mulDiv(amount1, FixedPoint96_Q96, sqrtUpper.subtract(sqrtLower));
    }

    public BigInteger getLiquidityForAmounts(BigInteger sqrtPrice, BigInteger sqrtLower, BigInteger sqrtUpper, BigInteger amount0, BigInteger amount1) {
        if (sqrtLower.compareTo(sqrtUpper) > 0) {
            BigInteger temp = sqrtUpper;
            sqrtUpper = sqrtLower;
            sqrtLower = temp;
        }

        BigInteger liquidity;
        if (sqrtPrice.compareTo(sqrtLower) <= 0) {
            liquidity = getLiquidityForAmount0(sqrtLower, sqrtUpper, amount0);
        } else if (sqrtPrice.compareTo(sqrtUpper) < 0) {
            BigInteger liquidity0 = getLiquidityForAmount0(sqrtPrice, sqrtUpper, amount0);
            BigInteger liquidity1 = getLiquidityForAmount1(sqrtLower, sqrtPrice, amount1);
            liquidity = liquidity0.compareTo(liquidity1) < 0 ? liquidity0 : liquidity1;
        } else {
            liquidity = getLiquidityForAmount1(sqrtLower, sqrtUpper, amount1);
        }
        return liquidity;
    }

    // r0/r1 = (a0 -x) / (a1 + y);
    // y/x = p
    public Tuple2<BigInteger, Integer> getTrimInfo(BigInteger r0, BigInteger r1, BigInteger a0, BigInteger a1, BigInteger p, BigDecimal poolFee) {
        BigDecimal amt = BigDecimal.ZERO;
        int token = 0;

        BigDecimal r00 = new BigDecimal(r0);
        BigDecimal r11 = new BigDecimal(r1);
        BigDecimal a00 = new BigDecimal(a0);
        BigDecimal a11 = new BigDecimal(a1);
        BigDecimal p0 = new BigDecimal(p);

        if (a0.multiply(r1).compareTo(a1.multiply(r0)) > 0) {
            // a0 / a1 > r0 / r1  token0>token1 , should turn into token1
            BigDecimal part1 = a00.multiply(r11).subtract(a11.multiply(r00));
            BigDecimal part2 = p0.multiply(r00).multiply(BigDecimal.ONE.subtract(poolFee)).add(r11);
            amt = part1.divide(part2, 18, RoundingMode.DOWN);
        } else if (a1.multiply(r0).compareTo(a0.multiply(r1)) > 0) {
            // a0 / a1 < r0 / r1  token1>token0, should turn into token0
            BigDecimal part1 = a11.multiply(r00).subtract(a00.multiply(r11));
            BigDecimal part2 = r11.multiply(BigDecimal.ONE.subtract(poolFee)).divide(p0, 18, RoundingMode.DOWN).add(r00);
            amt = part1.divide(part2, 18, RoundingMode.DOWN);
            token = 1;
        }
        return new Tuple2<>(amt.toBigInteger(), token);
    }

    private Tuple7<BigInteger, BigInteger, BigInteger, BigInteger, BigInteger, BigInteger, Integer> addLiquidity(Pool poolInfo, BigInteger sqrtPrice, BigInteger sqrtLower, BigInteger sqrtUpper, BigInteger price, BigInteger total0, BigInteger total1, BigDecimal poolFee) {
        Tuple2<BigInteger, BigInteger> flatAmounts = getAmountsForLiquidity(sqrtPrice, sqrtLower, sqrtUpper, BigInteger.TEN.pow(18));
        BigInteger r0 = flatAmounts.component1();
        BigInteger r1 = flatAmounts.component2();

        Tuple2<BigInteger, Integer> trimInfo = getTrimInfo(r0, r1, total0, total1, price, poolFee);
        BigInteger swapAmount = trimInfo.component1();
        BigInteger swapFee = new BigDecimal(swapAmount).multiply(poolInfo.getSwapFee()).toBigInteger();
        Integer tokenId = trimInfo.component2();
        if (tokenId == 0) {
            // x -> y
            total0 = total0.subtract(swapAmount);
            total1 = total1.add(swapAmount.subtract(swapFee).multiply(price));
        } else {
            // y - > x
            total1 = total1.subtract(swapAmount);
            total0 = total0.add(swapAmount.subtract(swapFee).divide(price));
        }

        // staking
        BigInteger liquidity = getLiquidityForAmounts(sqrtPrice, sqrtLower, sqrtUpper, total0, total1);
        Tuple2<BigInteger, BigInteger> tp2 = getAmountsForLiquidity(sqrtPrice, sqrtLower, sqrtUpper, liquidity);
        BigInteger amount0 = tp2.component1();
        BigInteger amount1 = tp2.component2();
        return new Tuple7<>(liquidity, amount0, amount1, swapFee, total0.subtract(amount0), total1.subtract(amount1), tokenId);
    }

    private Tuple3<BigInteger, BigInteger, BigDecimal> removeLiquidity(BigInteger sqrtPrice, BigInteger sqrtLower, BigInteger sqrtUpper, BigInteger liquidity, BigInteger price, BigInteger before0, BigInteger before1) {
        Tuple2<BigInteger, BigInteger> tp2 = getAmountsForLiquidity(sqrtPrice, sqrtLower, sqrtUpper, liquidity);
        BigInteger amount0 = tp2.component1();
        BigInteger amount1 = tp2.component2();
        BigDecimal netValue0 = new BigDecimal(amount1.add(amount0.multiply(price)));
        BigDecimal netValue1 = new BigDecimal(before1.add(before0.multiply(price)));
        BigDecimal im = BaseUtil.safeDivide(netValue0, netValue1).subtract(BigDecimal.ONE);
        return new Tuple3<>(amount0, amount1, im);
    }
}
