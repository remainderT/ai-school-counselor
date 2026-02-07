package org.buaa.rag.controller;

import java.util.List;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.dto.req.UserLoginReqDTO;
import org.buaa.rag.dto.req.UserRegisterReqDTO;
import org.buaa.rag.dto.req.UserUpdateReqDTO;
import org.buaa.rag.dto.resp.UserLoginRespDTO;
import org.buaa.rag.dto.resp.UserRespDTO;
import org.buaa.rag.service.UserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 用户管理控制层
 */
@RestController
@RequestMapping("/api/rag/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 注册用户
     */
    @PostMapping("")
    public Result<Void> register(@RequestBody UserRegisterReqDTO requestParam) {
        userService.register(requestParam);
        return Results.success();
    }

    /**
     * 注册时候获得验证码
     */
    @GetMapping("/send-code")
    public Result<Boolean> sendCode(@RequestParam("mail") String mail) {
        return Results.success(userService.sendCode(mail));
    }

    /**
     * 根据邮箱查找用户信息
     */
    @GetMapping("/{mail}")
    public Result<UserRespDTO> getUserByMail(@PathVariable("mail") String mail) {
        return Results.success(userService.getUserByMail(mail));
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<UserLoginRespDTO> login(@RequestBody UserLoginReqDTO requestParam, ServletRequest request) {
        return Results.success(userService.login(requestParam, request));
    }

    /**
     * 登陆时候获取验证码
     */
    @GetMapping("/kaptcha/")
    public void getKaptcha(HttpServletResponse response) {
        userService.getKaptcha(response);
    }

    /**
     * 检查用户是否登录
     */
    @GetMapping("/check-login")
    public Result<Boolean> checkLogin(@RequestParam("username") String username, @RequestParam("token") String token) {
        return Results.success(userService.checkLogin(username, token));
    }

    /**
     * 用户退出登录
     */
    @DeleteMapping("/logout")
    public Result<Void> logout(@RequestParam("username") String username, @RequestParam("token") String token) {
        userService.logout(username, token);
        return Results.success();
    }

    /**
     * 更新用户信息
     */
    @PutMapping("")
    public Result<Void> update(@RequestBody UserUpdateReqDTO requestParam) {
        userService.update(requestParam);
        return Results.success();
    }
}

