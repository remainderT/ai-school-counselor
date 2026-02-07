package org.buaa.rag.service;

import org.buaa.rag.dao.entity.UserDO;
import org.buaa.rag.dto.req.UserLoginReqDTO;
import org.buaa.rag.dto.req.UserRegisterReqDTO;
import org.buaa.rag.dto.req.UserUpdateReqDTO;
import org.buaa.rag.dto.resp.UserLoginRespDTO;
import org.buaa.rag.dto.resp.UserRespDTO;

import com.baomidou.mybatisplus.extension.service.IService;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface UserService extends IService<UserDO> {

    /**
     * 注册用户
     */
    void register(UserRegisterReqDTO requestParam);

    /**
     * 注册时候获得验证码
     */
    Boolean sendCode(String mail);

    /**
     * 根据邮箱查询用户信息
     */
    UserRespDTO getUserByMail(String mail);

    /**
     *  查询邮箱是否已注册
     */
    Boolean hasMail(String email);

    /**
     * 用户登录
     */
    UserLoginRespDTO login(UserLoginReqDTO requestParam, ServletRequest request);

    /**
     * 检查用户是否登录
     */
    Boolean checkLogin(String username, String token);

    /**
     * 用户退出登录
     */
    void logout(String username, String token);

    /**
     * 更新用户信息
     */
    void update(UserUpdateReqDTO requestParam);

    /**
     * 登陆时候获取图片验证码
     */
    void getKaptcha(HttpServletResponse response);

}
