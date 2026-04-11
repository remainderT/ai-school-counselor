package org.buaa.rag.common.user;

import static org.buaa.rag.common.consts.CacheConstants.USER_INFO_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_LOGIN_EXPIRE_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_LOGIN_KEY;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.buaa.rag.dao.entity.UserDO;
import org.buaa.rag.dao.mapper.UserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

/**
 * 刷新 Token 过滤器
 */
@RequiredArgsConstructor
public class RefreshTokenFilter implements Filter {

    private final StringRedisTemplate stringRedisTemplate;

    private final UserMapper userMapper;

    @SneakyThrows
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        String username = httpServletRequest.getHeader("username");
        String token = httpServletRequest.getHeader("token");
        if (StrUtil.isBlank(token) || StrUtil.isBlank(username)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        String hasLogin = stringRedisTemplate.opsForValue().get(USER_LOGIN_KEY + username);
        if (StrUtil.isBlank(hasLogin) || !Objects.equals(hasLogin, token)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        UserDO userDO = JSON.parseObject(stringRedisTemplate.opsForValue().get(USER_INFO_KEY + username), UserDO.class);
        if (userDO == null) {
            userDO = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUsername, username));
            stringRedisTemplate.opsForValue().set(USER_INFO_KEY + username, JSON.toJSONString(userDO), USER_LOGIN_EXPIRE_KEY, TimeUnit.DAYS);
        }
        UserInfoDTO userInfoDTO = UserInfoDTO.builder()
                .userId(String.valueOf(userDO.getId()))
                .username(userDO.getUsername())
                .mail(userDO.getMail())
                .salt(userDO.getSalt())
                .token(token)
                .isAdmin(userDO.getIsAdmin())
                .build();
        UserContext.setUser(userInfoDTO);

        stringRedisTemplate.expire(USER_LOGIN_KEY + username, USER_LOGIN_EXPIRE_KEY, TimeUnit.DAYS);

        try {
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            UserContext.removeUser();
        }
    }

}
