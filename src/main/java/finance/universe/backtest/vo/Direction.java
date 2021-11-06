package finance.universe.backtest.vo;

import lombok.Data;

import java.util.Objects;

/**
 * @author universe.finance
 * @version v1 2021/11/5.
 */
@Data
public class Direction implements Comparable<Direction> {
    private Long ts;
    private Integer direction;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Direction direction = (Direction) o;
        return ts.equals(direction.ts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ts);
    }

    @Override
    public int compareTo(Direction o) {
        return this.ts.compareTo(o.ts);
    }
}
