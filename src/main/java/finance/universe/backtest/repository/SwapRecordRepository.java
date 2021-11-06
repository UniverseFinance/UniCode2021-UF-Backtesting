package finance.universe.backtest.repository;

import finance.universe.backtest.entity.SwapRecord;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
public interface SwapRecordRepository extends CrudRepository<SwapRecord, String> {
    List<SwapRecord> findByPairAndTsGreaterThanEqualAndTsLessThanEqual(String pair, Long start, Long end);
}
