package finance.universe.backtest.entity;

import lombok.Data;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
@Data
@Entity
public class SwapRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String pair;
    private Long ts;
    private BigDecimal amount0;
    private BigDecimal amount1;
    private Long tick;
    private Long blockNumber;
    @Transient
    private BigDecimal price;
    @Transient
    private BigDecimal liquidity;
    private BigDecimal gasPrice;
}