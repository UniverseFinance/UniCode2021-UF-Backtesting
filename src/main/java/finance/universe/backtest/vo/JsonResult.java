package finance.universe.backtest.vo;

/**
 * @author universe.finance
 * @version v1 2021/11/4.
 */
public class JsonResult<T> {
    private Integer code;
    private String msg;
    private T data;
    private static final int success = 200;
    private static volatile JsonResult okInstance = null;

    public static JsonResult ok() {
        if (okInstance == null) {
            synchronized(JsonResult.class) {
                if (okInstance == null) {
                    okInstance = new JsonResult(success);
                }
            }
        }
        return okInstance;
    }

    public JsonResult() {
    }

    public JsonResult(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public JsonResult(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public JsonResult(Integer code) {
        this.code = code;
    }

    public Integer getCode() {
        return this.code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return this.msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return this.data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public static JsonResult success(Object data) {
        return new JsonResult(success, (String)null, data);
    }

    public static JsonResult success(String msg, Object data) {
        return new JsonResult(success, msg, data);
    }

    public static JsonResult error(Integer code, String msg) {
        return new JsonResult(code, msg);
    }

    public boolean isSuccess() {
        return null != this.getCode() && success == this.getCode();
    }
}
