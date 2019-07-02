package com.berry.oss.security.access;

import com.berry.oss.common.constant.Constants;
import com.berry.oss.common.utils.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Created with IntelliJ IDEA.
 *
 * @author Berry_Cooper.
 * @date 2019-06-30 14:54
 * fileName：AccessInterceptor
 * Use：
 */
public class AccessInterceptor implements HandlerInterceptor {

    private final AccessProvider accessProvider;

    public AccessInterceptor(AccessProvider accessProvider) {
        this.accessProvider = accessProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IllegalAccessException {
        String requestUrl = request.getRequestURI();
        if (Constants.WRITE_LIST.stream().noneMatch(requestUrl::matches)) {
            // sdk 通用请求拦截器,sdk token 验证后将不验证 upload token
            String ossAuth = request.getHeader(Constants.OSS_SDK_AUTH_HEAD_NAME);
            if (StringUtils.isNotBlank(ossAuth)) {
                // 验证 token 合法性
                this.accessProvider.validateSdkAuthentication(request);
            } else {
                // 上传 token 拦截器
                String accessToken = request.getHeader(Constants.ACCESS_TOKEN_KEY);
                if (StringUtils.isNotBlank(accessToken)) {
                    // 验证 token 合法性
                    this.accessProvider.validateUploadAuthentication(requestUrl);
                }
            }
        }
        return true;
    }
}
