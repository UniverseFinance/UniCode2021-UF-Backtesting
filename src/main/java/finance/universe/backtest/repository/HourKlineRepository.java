package finance.universe.backtest.repository;

import finance.universe.backtest.entity.HourKline;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
public interface HourKlineRepository extends CrudRepository<HourKline, Long> {

    List<HourKline> findByPairAndTsLessThanEqualOrderByTs(String lastName,Long ts);

    @Query("select max(ts) from HourKline where pair = ?1")
    Long getMaxTsByPair(String pair);
}
