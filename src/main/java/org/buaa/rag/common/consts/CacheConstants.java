package org.buaa.rag.common.consts;

/**
 * Redis缓存常量
 */
public class CacheConstants {

    /**
     * 用户注册验证码缓存
     */
    public static final String USER_REGISTER_CODE_KEY = "rag:user:register:code:";

    /**
     * 用户注册验证码缓存过期时间
     */
    public static final long USER_REGISTER_CODE_EXPIRE_KEY = 5L;

    /**
     * 用户重置密码验证码缓存
     */
    public static final String USER_RESET_CODE_KEY = "rag:user:reset:code:";

    /**
     * 用户登录图片验证码
     */
    public static final String USER_LOGIN_KAPTCHA_KEY = "rag:user:login:kaptcha:";


    /**
     * 用户重置密码验证码缓存过期时间
     */
    public static final long USER_RESET_CODE_EXPIRE_KEY = 5L;

    /**
     * 用户登录缓存标识
     */
    public static final String USER_LOGIN_KEY = "rag:user:login:";

    /**
     * 用户登录缓存过期时间(天)
     */
    public static final long USER_LOGIN_EXPIRE_KEY = 30L;

    /**
     * 用户个人信息缓存标识
     */
    public static final String USER_INFO_KEY = "rag:user:info:";

    /**
     * 意图树缓存标识
     */
    public static final String INTENT_TREE_KEY = "rag:intent:tree";

    /**
     * 意图树缓存过期时间（天）
     */
    public static final long INTENT_TREE_EXPIRE_DAYS = 7L;

}
