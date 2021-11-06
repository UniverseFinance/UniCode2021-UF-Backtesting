package finance.universe.backtest.controller;

import finance.universe.backtest.repository.PoolRepository;
import finance.universe.backtest.service.BackTestService;
import finance.universe.backtest.vo.BackTestTickParams;
import finance.universe.backtest.vo.JsonResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
@Slf4j
@RestController
public class IndexController {
    @Autowired
    BackTestService service;
    @Autowired
    PoolRepository repository;

    @PostMapping(value = {"/backtest"})
    public JsonResult backtest(@RequestBody BackTestTickParams params) {
        try {
            Map<String, Object> result = service.doBackTest(params);
            return JsonResult.success(result);
        } catch (Exception e) {
            log.error("backTest error", e);
            return JsonResult.error(500, "Server Error!");
        }
    }
}
