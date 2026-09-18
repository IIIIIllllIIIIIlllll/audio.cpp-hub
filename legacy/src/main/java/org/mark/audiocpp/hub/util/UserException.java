package org.mark.audiocpp.hub.util;

import java.util.Map;

/**
 * 用户可见错误：携带机器可读 code 与参数，前端按语言翻译；
 * message 为中文兜底文案（用于日志和旧前端的 error 字段）。
 */
public class UserException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    
	private final String code;
    private final Map<String, Object> params;

    public UserException(String code, String message) {
        this(code, Map.of(), message);
    }

    public UserException(String code, Map<String, Object> params, String message) {
        super(message);
        this.code = code;
        this.params = params == null ? Map.of() : params;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getParams() {
        return params;
    }
}
