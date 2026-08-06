package com.github.paicoding.forum.web.front.login.pwd; // 声明当前类所在的包路径，用于组织前台账号密码登录相关的 Web 入口。

import com.github.paicoding.forum.api.model.context.ReqInfoContext; // 引入请求上下文工具，用于获取当前请求中的登录用户、会话等信息。
import com.github.paicoding.forum.api.model.vo.ResVo; // 引入统一响应对象，用于包装接口返回结果。
import com.github.paicoding.forum.api.model.vo.constants.StatusEnum; // 引入状态码枚举，用于返回登录失败等业务错误。
import com.github.paicoding.forum.api.model.vo.user.UserPwdLoginReq; // 引入账号密码注册或绑定请求对象。
import com.github.paicoding.forum.core.permission.Permission; // 引入权限校验注解，用于限制接口访问角色。
import com.github.paicoding.forum.core.permission.UserRole; // 引入用户角色枚举，用于声明接口需要的登录状态。
import com.github.paicoding.forum.core.util.SessionUtil; // 引入会话工具类，用于创建和删除登录 Cookie。
import com.github.paicoding.forum.service.user.service.LoginService; // 引入登录业务服务，负责账号密码登录、注册和退出。
import com.github.paicoding.forum.service.user.service.audit.UserShareRiskControlService; // 引入账号共享风控服务，用于生成高风险登录提示。
import com.github.paicoding.forum.web.front.login.zsxq.helper.ZsxqHelper; // 引入知识星球登录辅助类，用于构建第三方登录地址。
import org.apache.commons.lang3.StringUtils; // 引入字符串工具类，用于判断字符串是否为空。
import org.springframework.beans.factory.annotation.Autowired; // 引入 Spring 自动注入注解，用于注入 Service Bean。
import org.springframework.web.bind.annotation.PostMapping; // 引入 POST 请求映射注解，用于声明 POST 接口。
import org.springframework.web.bind.annotation.RequestMapping; // 引入通用请求映射注解，用于声明接口路径。
import org.springframework.web.bind.annotation.RequestParam; // 引入请求参数绑定注解，用于读取 URL 或表单参数。
import org.springframework.web.bind.annotation.RestController; // 引入 REST Controller 注解，使方法返回值自动序列化为响应体。

import javax.servlet.http.HttpServletRequest; // 引入 Servlet 请求对象，用于读取 Session 和请求头。
import javax.servlet.http.HttpServletResponse; // 引入 Servlet 响应对象，用于写 Cookie 和执行重定向。
import java.io.IOException; // 引入 IO 异常类型，用于声明重定向时可能抛出的异常。
import java.util.Optional; // 引入 Optional 工具类，用于优雅处理可能为空的对象。

/**
 * 用户名、密码方式的登录、注册、登出以及知识星球登录入口。
 *
 * @author YiHui
 * @date 2022/8/15
 */
@RestController // 标记该类为 REST 控制器，方法返回值会直接写入 HTTP 响应体。
@RequestMapping // 声明当前控制器的基础请求路径为空，方法上的路径直接作为完整路径使用。
public class LoginRestController { // 定义账号密码登录相关的控制器类。
    @Autowired // 让 Spring 自动注入登录业务服务实例。
    private LoginService loginService; // 保存登录业务服务，用于处理登录、注册、退出登录。
    @Autowired // 让 Spring 自动注入知识星球登录辅助类实例。
    private ZsxqHelper zsxqHelper; // 保存知识星球登录辅助对象，用于生成跳转 URL。
    @Autowired // 让 Spring 自动注入用户共享风控服务实例。
    private UserShareRiskControlService userShareRiskControlService; // 保存风控服务，用于获取高风险登录提示。

    /**
     * 用户名和密码登录。
     * 可以根据星球编号或用户名进行密码匹配。
     */
    @PostMapping("/login/username") // 将该方法映射为 POST /login/username 接口。
    public ResVo<Boolean> login(@RequestParam(name = "username") String username, // 从请求参数中读取 username。
                                @RequestParam(name = "password") String password, // 从请求参数中读取 password。
                                HttpServletResponse response) { // 注入 HTTP 响应对象，用于向浏览器写入 Cookie。
        String session = loginService.loginByUserPwd(username, password); // 调用登录服务校验账号密码，并返回登录会话标识。
        if (StringUtils.isNotBlank(session)) { // 判断登录服务是否返回了有效会话，非空表示登录成功。
            // cookie 中写入用户登录信息，用于后续请求的身份识别。
            response.addCookie(SessionUtil.newCookie(LoginService.SESSION_KEY, session)); // 创建登录 Cookie 并添加到响应中。
            ResVo<Boolean> vo = ResVo.ok(true); // 构建登录成功的统一响应对象。
            String riskTip = userShareRiskControlService.getHighRiskLoginTip(ReqInfoContext.getReqInfo().getUserId()); // 根据当前用户 ID 查询是否有高风险登录提示。
            if (StringUtils.isNotBlank(riskTip)) { // 判断风控提示是否存在。
                vo.getStatus().setMsg(riskTip); // 将风控提示写入响应状态信息，前端可展示给用户。
            } // 结束风控提示判断分支。
            return vo; // 返回登录成功响应。
        } else { // 登录服务没有返回有效会话，说明登录失败。
            return ResVo.fail(StatusEnum.LOGIN_FAILED_MIXED, "用户名和密码登录异常，请稍后重试"); // 返回登录失败响应。
        } // 结束登录成功与失败的分支判断。
    } // 结束用户名密码登录方法。

    /**
     * 注册或绑定账号密码登录信息。
     */
    @PostMapping("/login/register") // 将该方法映射为 POST /login/register 接口。
    public ResVo<Long> register(UserPwdLoginReq loginReq, // 接收账号密码注册请求参数。
                                HttpServletResponse response) { // 注入 HTTP 响应对象，用于向浏览器写入 Cookie。
        String session = loginService.registerByUserPwd(loginReq); // 调用登录服务完成账号密码注册或绑定，并返回会话标识。
        if (StringUtils.isNotBlank(session)) { // 判断注册或绑定后是否生成了有效会话。
            // cookie 中写入用户登录信息，用于后续请求的身份识别。
            response.addCookie(SessionUtil.newCookie(LoginService.SESSION_KEY, session)); // 创建登录 Cookie 并添加到响应中。
            // 获取当前登录用户的 ID。
            Long userId = ReqInfoContext.getReqInfo().getUserId(); // 从请求上下文中获取当前用户 ID。
            return ResVo.ok(userId); // 返回注册或绑定成功后的用户 ID。
        } else { // 注册或绑定失败，没有生成有效会话。
            return ResVo.fail(StatusEnum.LOGIN_FAILED_MIXED, "用户名和密码登录异常，请稍后重试"); // 返回登录失败响应。
        } // 结束注册成功与失败的分支判断。
    } // 结束注册或绑定账号密码方法。

    @Permission(role = UserRole.LOGIN) // 声明该接口需要登录用户才能访问。
    @RequestMapping("logout") // 将该方法映射为 /logout 接口。
    public ResVo<Boolean> logOut(HttpServletRequest request, HttpServletResponse response) throws IOException { // 定义退出登录方法，并声明重定向可能抛出 IO 异常。
        // 释放服务器端 Session。
        request.getSession().invalidate(); // 使当前 HTTP Session 失效。
        Optional.ofNullable(ReqInfoContext.getReqInfo()).ifPresent(s -> loginService.logout(s.getSession())); // 如果请求上下文存在，则调用登录服务清理业务会话。
        // 移除登录 Cookie。
        SessionUtil.delCookies(LoginService.SESSION_KEY); // 删除登录会话对应的 Cookie。
        // 重定向到当前页面。
        String referer = request.getHeader("Referer"); // 从请求头中读取来源页面地址。
        if (StringUtils.isBlank(referer)) { // 判断来源页面是否为空。
            referer = "/"; // 如果来源页面为空，则默认跳转到首页。
        } // 结束来源页面兜底判断。
        response.sendRedirect(referer); // 将浏览器重定向回来源页面或首页。
        return ResVo.ok(true); // 返回退出成功响应；重定向场景下前端通常不会消费这个 JSON。
    } // 结束退出登录方法。

    /**
     * 知识星球登录。
     */
    @RequestMapping("login/zsxq") // 将该方法映射为 /login/zsxq 接口。
    public void redirectToZsxq(HttpServletResponse response) throws IOException { // 定义知识星球登录跳转方法，并声明重定向可能抛出 IO 异常。
        String url = zsxqHelper.buildZsxqLoginUrl("login"); // 构建知识星球登录授权地址。
        response.sendRedirect(url); // 将浏览器重定向到知识星球登录授权地址。
    } // 结束知识星球登录跳转方法。
} // 结束 LoginRestController 类定义。
