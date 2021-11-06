package finance.universe.backtest.entity;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.Id;
import java.math.BigDecimal;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
@Data
@Entity
public class Pool {
    @Id
    private String pair;
    private String token0;
    private String token1;
    private int decimal0;
    private int decimal1;
    private int reverse;
    private int tickSpacing;
    private BigDecimal swapFee;

    public boolean testReverse() {
        return reverse == 1;
    }

    public int decimalDiff() {
        return decimal1 - decimal0;
    }
}
