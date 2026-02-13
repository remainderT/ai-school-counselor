package org.buaa.rag.service.impl;

import static org.buaa.rag.common.consts.CacheConstants.USER_INFO_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_LOGIN_EXPIRE_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_LOGIN_KAPTCHA_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_LOGIN_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_REGISTER_CODE_EXPIRE_KEY;
import static org.buaa.rag.common.consts.CacheConstants.USER_REGISTER_CODE_KEY;
import static org.buaa.rag.common.consts.SystemConstants.MAIL_SUFFIX;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.IMAGE_UPLOAD_ERROR;
import static org.buaa.rag.common.enums.ServiceErrorCodeEnum.MAIL_SEND_ERROR;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_CODE_ERROR;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_LOGIN_KAPTCHA_ERROR;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_MAIL_EXIST;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_PASSWORD_ERROR;
import static org.buaa.rag.common.enums.UserErrorCodeEnum.USER_TOKEN_NULL;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.buaa.rag.common.consts.SystemConstants;
import org.buaa.rag.common.convention.exception.ClientException;
import org.buaa.rag.common.enums.UserErrorCodeEnum;
import org.buaa.rag.common.convention.exception.ServiceException;
import org.buaa.rag.common.user.UserContext;
import org.buaa.rag.dao.entity.UserDO;
import org.buaa.rag.dao.mapper.UserMapper;
import org.buaa.rag.dto.req.UserLoginReqDTO;
import org.buaa.rag.dto.req.UserRegisterReqDTO;
import org.buaa.rag.dto.req.UserUpdateReqDTO;
import org.buaa.rag.dto.resp.UserLoginRespDTO;
import org.buaa.rag.dto.resp.UserRespDTO;
import org.buaa.rag.service.UserService;
import org.buaa.rag.tool.RandomGenerator;
import org.springframework.beans.BeanUtils;
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
import com.google.code.kaptcha.Producer;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 用户接口实现层
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserService {

    private final JavaMailSender mailSender;
    private final StringRedisTemplate stringRedisTemplate;
    private final Producer kapchaProducer;

    @Value("${spring.mail.username}")
    private String from;

    @Override
    public UserRespDTO getUserByMail(String mail) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getMail, mail);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ServiceException(UserErrorCodeEnum.USER_NULL);
        }
        UserRespDTO result = new UserRespDTO();
        BeanUtils.copyProperties(userDO, result);
        return result;
    }

    @Override
    public Boolean hasMail(String mail) {
          LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getMail, mail);
         UserDO userDO = baseMapper.selectOne(queryWrapper);
         return userDO != null;
    }

    @Override
    public Boolean sendCode(String mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        String code = RandomGenerator.generateSixDigitCode();
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
            System.out.println(e);
            throw new ServiceException(MAIL_SEND_ERROR);
        }
    }

    @Override
    public void register(UserRegisterReqDTO requestParam) {
        String code = requestParam.getCode();
        String key = USER_REGISTER_CODE_KEY + requestParam.getMail().replace(MAIL_SUFFIX, "");
        String cacheCode = stringRedisTemplate.opsForValue().get(key);
        if (!code.equals(cacheCode)) {
            throw new ClientException(USER_CODE_ERROR);
        }
        if (hasMail(requestParam.getMail())) {
            throw new ClientException(USER_MAIL_EXIST);
        }
        try {
            UserDO userDO = BeanUtil.toBean(requestParam, UserDO.class);
            userDO.setSalt(UUID.randomUUID().toString().substring(0, 5));
            userDO.setPassword(DigestUtils.md5DigestAsHex((userDO.getPassword() + userDO.getSalt()).getBytes()));
            baseMapper.insert(userDO);
            userDO = baseMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                    .eq(UserDO::getUsername, requestParam.getUsername()));
            stringRedisTemplate.opsForValue().set(USER_INFO_KEY + requestParam.getUsername(), JSON.toJSONString(userDO));
        } catch (DuplicateKeyException ex) {
            throw new ClientException(USER_MAIL_EXIST);
        }
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam, ServletRequest request) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        Cookie[] cookies = httpRequest.getCookies();
        String kaptchaOwner = "";
        if (cookies != null) {
            kaptchaOwner = Arrays.stream(cookies)
                    .filter(cookie -> "KaptchaOwner".equals(cookie.getName()))
                    .findFirst()
                    .map(Cookie::getValue)
                    .orElse(null);
        }

        String code = stringRedisTemplate.opsForValue().get(USER_LOGIN_KAPTCHA_KEY + kaptchaOwner);
        if (StrUtil.isBlank(code) || !code.equalsIgnoreCase(requestParam.getCode())) {
            throw new ClientException(USER_LOGIN_KAPTCHA_ERROR);
        }

        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername());
        UserDO userDO = baseMapper.selectOne(queryWrapper);

        String password = DigestUtils.md5DigestAsHex((requestParam.getPassword() + userDO.getSalt()).getBytes());
        if (!Objects.equals(userDO.getPassword(), password)) {
            throw new ClientException(USER_PASSWORD_ERROR);
        }

        /**
         * String
         * Key：user:login:username
         * Value: token标识
         */
        String hasLogin = stringRedisTemplate.opsForValue().get(USER_LOGIN_KEY + requestParam.getUsername());
        if (StrUtil.isNotEmpty(hasLogin)) {
            return new UserLoginRespDTO(hasLogin);
        }
        String uuid = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(USER_LOGIN_KEY + requestParam.getUsername(), uuid, USER_LOGIN_EXPIRE_KEY, TimeUnit.DAYS);
        return new UserLoginRespDTO(uuid);
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
        stringRedisTemplate.opsForValue().set(USER_INFO_KEY + requestParam.getNewUsername(), JSON.toJSONString(newUserDO));
    }

    @Override
    public void getKaptcha(HttpServletResponse response) {
        String text = kapchaProducer.createText();
        BufferedImage image = kapchaProducer.createImage(text);

        String CaptchaOwner = UUID.randomUUID().toString();
        Cookie cookie = new Cookie("CaptchaOwner", CaptchaOwner);
        cookie.setMaxAge(60);
        response.addCookie(cookie);

        String redisKey = USER_LOGIN_KAPTCHA_KEY + CaptchaOwner;
        stringRedisTemplate.opsForValue().set(redisKey, text, 60, TimeUnit.SECONDS);

        response.setContentType("image/png");
        try {
            ServletOutputStream os = response.getOutputStream();
            ImageIO.write(image, "png", os);
        } catch (IOException e) {
            throw new ServiceException(IMAGE_UPLOAD_ERROR);
        }
    }
}
