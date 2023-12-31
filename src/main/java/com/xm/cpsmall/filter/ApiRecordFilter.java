package com.xm.cpsmall.filter;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSON;
import com.xm.cpsmall.comm.mq.message.config.WindMqConfig;
import com.xm.cpsmall.module.user.serialize.entity.SuUserEntity;
import com.xm.cpsmall.module.wind.serialize.entity.SwApiRecordEntity;
import com.xm.cpsmall.utils.IpUtil;
import com.xm.cpsmall.utils.RequestHeaderConstant;
import com.xm.cpsmall.utils.request.RequestWrapper;
import com.xm.cpsmall.utils.response.ResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 风控系统
 * 记录用户的api请求
 */
@Slf4j
@WebFilter(
        filterName = "apiRecordFilter",
        urlPatterns = {"*"}
)
@Component
public class ApiRecordFilter implements Filter {

    @Autowired
    private RabbitTemplate rabbitTemplate;


    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        //该filter最先执行，所以此处替换servletRequest，servletResponse
        RequestWrapper request = new RequestWrapper((HttpServletRequest) servletRequest);
        ResponseWrapper response = servletResponse instanceof ResponseWrapper ? (ResponseWrapper) servletResponse : new ResponseWrapper((HttpServletResponse) servletResponse);

        Object userObj = request.getSession().getAttribute(RequestHeaderConstant.USER_INFO);
        SwApiRecordEntity entity = new SwApiRecordEntity();
        //设置userId
        Integer userId = null;
        if(ObjectUtil.isNotEmpty(userObj))
            userId = ((SuUserEntity)userObj).getId();
        entity.setUserId(userId);
        //设置appType
        if(request.getRequestURL().toString().contains("/manage/")){
            entity.setAppType(9);
        }else {
            String appType = request.getHeader(RequestHeaderConstant.APP_TYPE);
            entity.setAppType(appType != null ? Integer.valueOf(appType) : null);
        }
        //设置IP
        entity.setIp(IpUtil.getIp(request));
        //设置URI
        entity.setUrl(request.getRequestURI());
        //设置method
        entity.setMethod(request.getMethod());
        //设置参数
        if("GET".equals(request.getMethod())){
            Map<String,Object> params = new HashMap<>();
            Enumeration<String> enumeration = request.getParameterNames();
            while (enumeration.hasMoreElements()){
                String paramName = enumeration.nextElement();
                params.put(paramName,request.getParameter(paramName));
            }
            entity.setParam(params.isEmpty() ? null : JSON.toJSONString(params));
        }else if("POST".equals(request.getMethod())){
            String requestBody = null;
            try {
                requestBody = IoUtil.read(request.getInputStream(),"UTF-8");
            } catch (IOException e) {
                e.printStackTrace();
            }
            entity.setParam(requestBody);
        }else {
            filterChain.doFilter(request,response);
            return;
        }
        long startTime = System.currentTimeMillis();
        filterChain.doFilter(request,response);

        //设置执行时间
        entity.setTime( Integer.valueOf((System.currentTimeMillis() - startTime) + ""));
        //设置结果
        Boolean should = servletResponse.getContentType() == null ? false : servletResponse.getContentType().contains("application/json");
        if(should){
            entity.setResult(new String(response.getContent()));
        }
        entity.setUa(request.getHeader("User-Agent"));
        entity.setCreateTime(new Date());
        rabbitTemplate.convertAndSend(WindMqConfig.EXCHANGE,WindMqConfig.KEY_API,entity);
    }

    @Override
    public void destroy() {

    }
}
