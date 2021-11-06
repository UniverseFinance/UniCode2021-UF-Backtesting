package finance.universe.backtest.vo;

import lombok.Data;

import java.util.Objects;

/**
 * @author Stanley @ universe.finance
 * @version v1 2021/11/5.
 */
@Data
public class Rebalance implements Comparable<Rebalance> {
    private Long block;
    private Integer upper;
    private Integer lower;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Rebalance rebalance = (Rebalance) o;
        return block.equals(rebalance.block);
    }

    @Override
    public int hashCode() {
        return Objects.hash(block);
    }

    @Override
    public int compareTo(Rebalance o) {
        return this.block.compareTo(o.block);
    }
}
