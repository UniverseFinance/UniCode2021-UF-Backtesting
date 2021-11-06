package finance.universe.backtest.util;

import finance.universe.backtest.entity.Pool;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
public class BaseUtil {

    public static BigDecimal safeDivide(BigDecimal d1, BigDecimal d2) {
        return d1.divide(d2, 36, RoundingMode.DOWN);
    }

    public static BigDecimal getAmountWithScale(BigInteger d, int decimal) {
        return new BigDecimal(d).divide(BigDecimal.valueOf(Math.pow(10, decimal)), decimal, RoundingMode.DOWN);
    }

    public static BigInteger getAmountByScale(BigDecimal d, int decimal) {
        return d.multiply(BigDecimal.valueOf(Math.pow(10, decimal))).toBigInteger();
    }

    public static BigDecimal getPriceByTick(Pool poolInfo, BigInteger tick) {
        BigDecimal a = BigDecimal.valueOf(Math.pow(1.0001, tick.intValue()));
        BigDecimal b = BigDecimal.valueOf(Math.pow(10, poolInfo.decimalDiff()));
        if (poolInfo.testReverse()) {
            return safeDivide(b, a);
        } else {
            return safeDivide(a, b);
        }
    }

    public static BigDecimal getCommissionRate(BigInteger amount0, BigInteger amount1, BigInteger c0, BigInteger c1, BigInteger price) {
        BigInteger n1 = amount1.add(amount0.multiply(price));
        BigInteger n2 = c1.add(c0.multiply(price));
        return safeDivide(new BigDecimal(n2), new BigDecimal(n1));
    }

    public static BigDecimal getNetValue(Pool poolInfo, BigDecimal amount0, BigDecimal amount1, BigDecimal price) {
        if (poolInfo.testReverse()) {
            return amount0.add(amount1.multiply(price));
        } else {
            return amount1.add(amount0.multiply(price));
        }
    }

    public static long alignedToHour(long ts) {
        long unit = TimeUnit.HOURS.toMillis(1);
        return ts / unit * unit;
    }

    public static BigInteger toGWei(BigInteger wei) {
        return wei.divide(BigInteger.TEN.pow(9));
    }

    public static BigDecimal toEther(BigInteger wei) {
        return safeDivide(new BigDecimal(wei), BigDecimal.TEN.pow(18));
    }

    public static Long floor(Long tick, int tickSpacing) {
        long compressed = tick / tickSpacing;
        if (tick < 0 && tick % tickSpacing != 0) compressed--;
        return compressed * tickSpacing;
    }

    public static BigInteger getSqrtPriceByTick(long tick) {
        return BigDecimal.valueOf(Math.sqrt(Math.pow(1.0001, tick)) * Math.pow(2, 96)).toBigInteger();
    }
}
