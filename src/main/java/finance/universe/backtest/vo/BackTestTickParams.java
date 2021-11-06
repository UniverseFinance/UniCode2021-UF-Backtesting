package finance.universe.backtest.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
@Data
public class BackTestTickParams {
    private String pair;
    private Long boundaryThreshold;
    private Long reBalanceThreshold;
    private Long startTs;
    private Long endTs;
    private BigDecimal amount0;
    private BigDecimal amount1;
    private List<Rebalance> rebalance;
}
