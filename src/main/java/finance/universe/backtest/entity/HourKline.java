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
@Table(name = "hour_kline")
public class HourKline {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String pair;
    private Long ts;
    private BigDecimal liquidity;
}
