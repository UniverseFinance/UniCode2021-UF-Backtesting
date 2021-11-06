package finance.universe.backtest.repository;

import finance.universe.backtest.entity.Pool;
import org.springframework.data.repository.CrudRepository;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
public interface PoolRepository extends CrudRepository<Pool, String> {

}
