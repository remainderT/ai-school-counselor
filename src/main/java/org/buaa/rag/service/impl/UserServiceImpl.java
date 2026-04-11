package org.buaa.rag.service.impl;

import static org.buaa.rag.common.consts.CacheConstants.USER_INFO_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_LOGIN_EXPIRE_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_LOGIN_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_REGISTER_CODE_EXPIRE_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_REGISTER_CODE_KEY;
import static org.buaa.rag.common.consts.SystemConstants.MAIL_SUFFIX;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.MAIL_SEND_ERROR;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_CODE_ERROR;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_MAIL_EXIST;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_NAME_EXIST;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_PASSWORD_ERROR;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_TOKEN_NULL;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.buaa.rag.common.consts.SystemConstants;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.enums.UserErrorCodeEnum;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.UserDO;
import org.buaa.rag.dao.mapper.UserMapper;
import org.buaa.rag.dto.req.UserLoginReqDTO;
import org.buaa.rag.dto.req.UserRegisterReqDTO;
import org.buaa.rag.dto.req.UserUpdateReqDTO;
import org.buaa.rag.dto.resp.UserLoginRespDTO;
import org.buaa.rag.dto.resp.UserRespDTO;
import org.buaa.rag.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.ServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 用户接口实现层
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserService {

    private final JavaMailSender mailSender;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${spring.mail.username}")
    private String from;

    private void cacheUserInfo(UserDO userDO) {
        if (userDO == null || StrUtil.isBlank(userDO.getUsername())) {
            return;
        }
        stringRedisTemplate.opsForValue().set(
                USER_INFO_KEY + userDO.getUsername(),
                JSON.toJSONString(userDO),
                USER_LOGIN_EXPIRE_KEY,
                TimeUnit.DAYS);
    }

    @Override
    public UserRespDTO getUserInfo(String username) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ServiceException(UserErrorCodeEnum.USER_NULL);
        }
        UserRespDTO result = BeanUtil.toBean(userDO, UserRespDTO.class);
        cacheUserInfo(userDO);
        return result;
    }

    @Override
    public Boolean hasMail(String mail) {
        return baseMapper.selectCount(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getMail, mail)) > 0;
    }

    @Override
    public Boolean hasUsername(String username) {
        return baseMapper.selectCount(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUsername, username)) > 0;
    }

    @Override
    public Boolean sendCode(String mail) {
        String code = String.format("%06d", (int) (Math.random() * 1_000_000));
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setText(String.format(SystemConstants.MAIL_TEXT, code));
        message.setTo(mail);
        message.setSubject(SystemConstants.MAIL_SUBJECT);
        try {
            mailSender.send(message);
            String key = USER_REGISTER_CODE_KEY + mail.replace(MAIL_SUFFIX, "");
            stringRedisTemplate.opsForValue().set(key, code, USER_REGISTER_CODE_EXPIRE_KEY, TimeUnit.MINUTES);
            return true;
        } catch (Exception e) {
            log.error("发送邮件失败", e);
            throw new ServiceException(MAIL_SEND_ERROR);
        }
    }

    @Override
    public synchronized void register(UserRegisterReqDTO requestParam) {
        String key = USER_REGISTER_CODE_KEY + requestParam.getMail().replace(MAIL_SUFFIX, "");
        String cacheCode = stringRedisTemplate.opsForValue().get(key);
        if (!requestParam.getCode().equals(cacheCode)) {
            throw new ClientException(USER_CODE_ERROR);
        }
        if (hasMail(requestParam.getMail())) {
            throw new ClientException(USER_MAIL_EXIST);
        }
        if (hasUsername(requestParam.getUsername())) {
            throw new ClientException(USER_NAME_EXIST);
        }
        try {
            UserDO userDO = BeanUtil.toBean(requestParam, UserDO.class);
            userDO.setSalt(UUID.randomUUID().toString().substring(0, 5));
            userDO.setPassword(DigestUtils.md5DigestAsHex((userDO.getPassword() + userDO.getSalt()).getBytes()));
            userDO.setIsAdmin(0);
            baseMapper.insert(userDO);
            // insert 后 userDO 已持有自增 id，直接缓存，无需二次查询
            cacheUserInfo(userDO);
        } catch (DuplicateKeyException ex) {
            throw new ClientException(USER_MAIL_EXIST);
        }
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam, ServletRequest request) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername());
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NULL);
        }

        String password = DigestUtils.md5DigestAsHex((requestParam.getPassword() + userDO.getSalt()).getBytes());
        if (!Objects.equals(userDO.getPassword(), password)) {
            throw new ClientException(USER_PASSWORD_ERROR);
        }

        // 已登录则刷新过期时间并复用原 token
        String loginKey = USER_LOGIN_KEY + requestParam.getUsername();
        String existingToken = stringRedisTemplate.opsForValue().get(loginKey);
        if (StrUtil.isNotEmpty(existingToken)) {
            stringRedisTemplate.expire(loginKey, USER_LOGIN_EXPIRE_KEY, TimeUnit.DAYS);
            cacheUserInfo(userDO);
            return new UserLoginRespDTO(existingToken, userDO.getIsAdmin());
        }
        String token = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(loginKey, token, USER_LOGIN_EXPIRE_KEY, TimeUnit.DAYS);
        cacheUserInfo(userDO);
        return new UserLoginRespDTO(token, userDO.getIsAdmin());
    }

    @Override
    public Boolean checkLogin(String mail, String token) {
        String hasLogin = stringRedisTemplate.opsForValue().get(USER_LOGIN_KEY + mail);
        if (StrUtil.isEmpty(hasLogin)) {
            return false;
        }
        return Objects.equals(hasLogin, token);
    }

    @Override
    public void logout(String mail, String token) {
        if (checkLogin(mail, token)) {
            stringRedisTemplate.delete(USER_LOGIN_KEY + mail);
            return;
        }
        throw new ClientException(USER_TOKEN_NULL);
    }

    @Override
    public void update(UserUpdateReqDTO requestParam) {
        String password = DigestUtils.md5DigestAsHex((requestParam.getPassword() + UserContext.getSalt()).getBytes());
        UserDO userDO = UserDO.builder()
                .username(requestParam.getNewUsername())
                .password(password)
                .avatar(requestParam.getAvatar())
                .build();
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getMail, UserContext.getMail());
        baseMapper.update(userDO, updateWrapper);
        UserDO newUserDO = baseMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getNewUsername()));
        cacheUserInfo(newUserDO);
    }
}
