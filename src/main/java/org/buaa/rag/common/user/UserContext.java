package org.buaa.rag.common.user;

import java.util.Optional;

/**
 * 用户上下文
 */
public final class UserContext {


    private static final ThreadLocal<UserInfoDTO> USER_THREAD_LOCAL = new ThreadLocal<>();

    public static void setUser(UserInfoDTO user) {
        USER_THREAD_LOCAL.set(user);
    }

    public static Long getUserId() {
        UserInfoDTO userInfoDTO = USER_THREAD_LOCAL.get();
        return Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getUserId).map(Long::valueOf).orElse(null);
    }

    public static String getUsername() {
        UserInfoDTO userInfoDTO = USER_THREAD_LOCAL.get();
        return Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getUsername).orElse(null);
    }

    public static String getToken() {
        UserInfoDTO userInfoDTO = USER_THREAD_LOCAL.get();
        return Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getToken).orElse(null);
    }

    public static String getSalt() {
        UserInfoDTO userInfoDTO = USER_THREAD_LOCAL.get();
        return Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getSalt).orElse(null);
    }

    public static String getMail() {
        UserInfoDTO userInfoDTO = USER_THREAD_LOCAL.get();
        return Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getMail).orElse(null);
    }

    public static boolean isAdmin() {
        UserInfoDTO userInfoDTO = USER_THREAD_LOCAL.get();
        Integer isAdmin = Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getIsAdmin).orElse(0);
        return Integer.valueOf(1).equals(isAdmin);
    }

    public static void removeUser() {
        USER_THREAD_LOCAL.remove();
    }
}
