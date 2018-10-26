package com.zjasm.cas;

import com.commnetsoft.IDMClient;
import com.commnetsoft.IDMConfig;
import com.commnetsoft.Prop;
import com.commnetsoft.model.IdValidationResult;
import com.github.javaparser.utils.Log;
import com.zjasm.captcha.UsernamePasswordCaptchaCredential;
import com.zjasm.exception.InvalidCaptchaException;
import com.zjasm.exception.NoAuthException;
import com.zjasm.model.PortTypeParams;
import com.zjasm.util.CommonUtil;
import com.zjasm.util.ConfigUtil;
import com.zjasm.util.Dbutil;
import com.zjasm.webservice.SimpleAuthService;
import com.zjasm.webservice.SimpleAuthServicePortType;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.AuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.PreventedException;
import org.apereo.cas.authentication.handler.support.AbstractPreAndPostProcessingAuthenticationHandler;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.core.authentication.JdbcPrincipalAttributesProperties;
import org.apereo.cas.configuration.model.support.jdbc.QueryJdbcAuthenticationProperties;
import org.apereo.cas.services.ServicesManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import javax.security.auth.login.FailedLoginException;
import javax.servlet.http.HttpSession;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@EnableConfigurationProperties(CasConfigurationProperties.class)
public class Login extends AbstractPreAndPostProcessingAuthenticationHandler {

    @Autowired
    private CasConfigurationProperties casProperties;

    public final static String IDM_KEY_GOV = "idm_gov";

    public Login(String name, ServicesManager servicesManager, PrincipalFactory principalFactory, Integer order) {
        super(name, servicesManager, principalFactory, order);
    }

    @Override
    protected AuthenticationHandlerExecutionResult doAuthentication(Credential credential) throws GeneralSecurityException, PreventedException {
        UsernamePasswordCaptchaCredential mycredential1 = (UsernamePasswordCaptchaCredential) credential;

        String captcha = mycredential1.getCaptcha();
        String orgcode = mycredential1.getOrgcode();
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String right = attributes.getRequest().getSession().getAttribute("captcha").toString();
        if(!captcha.equalsIgnoreCase(right)){
            mycredential1.setPassword("");//验证码错误，暂时无法登录处理，不然会凭借正确的用户名密码直接登录
            throw new InvalidCaptchaException("验证码不正确");
        }

        DriverManagerDataSource d=new DriverManagerDataSource();
        List<QueryJdbcAuthenticationProperties> jdbcProsList = casProperties.getAuthn().getJdbc().getQuery();
        QueryJdbcAuthenticationProperties jdbcPros = jdbcProsList.get(0);
        QueryJdbcAuthenticationProperties jdbcPros1 = jdbcProsList.get(1);
        QueryJdbcAuthenticationProperties jdbcPros2 = jdbcProsList.get(2);
        d.setDriverClassName(jdbcPros.getDriverClass());
        d.setUrl(jdbcPros.getUrl());
        d.setUsername(jdbcPros.getUser());
        d.setPassword(jdbcPros.getPassword());
        JdbcTemplate template=new JdbcTemplate();
        template.setDataSource(d);

        //获取服务的子系统链接service
        String serviceStr = attributes.getRequest().getParameter("service");
        //判断用户是否属于该系统
        if(serviceStr!=""&&serviceStr!=null){
            final List listSiteDomain = template.queryForList(jdbcPros1.getSql(),mycredential1.getUsername());
            if(listSiteDomain!=null&&!listSiteDomain.isEmpty()){
                for(int i=0;i<listSiteDomain.size();i++) {
                    String site = ((LinkedCaseInsensitiveMap)listSiteDomain.get(i)).get("site_domain").toString();
                    if(site!=null&&serviceStr.indexOf(site)!=-1){
                        break;
                    }
                    if(i==listSiteDomain.size()-1){
                        mycredential1.setPassword("");//验证码错误，暂时无法登录处理，不然会凭借正确的用户名密码直接登录
                        throw new NoAuthException("此用户无权限访问此系统");
                    }
                }
            }else {
                mycredential1.setPassword("");//验证码错误，暂时无法登录处理，不然会凭借正确的用户名密码直接登录
                throw new NoAuthException("此用户无权限访问此系统");
            }
        }


        String username=mycredential1.getUsername();
        String pwdStr = mycredential1.getPassword();

        //查询数据库加密的的密码
        Map<String,Object> user = template.queryForMap(jdbcPros.getSql(), mycredential1.getUsername());

        if(user==null){
            mycredential1.setPassword("");
            throw new FailedLoginException("没有该用户");
        }
        AuthenticationHandlerExecutionResult handlerResult = null;
        //获取验证开关
        ConfigUtil configs= ConfigUtil.getConfig("config.properties");
        boolean authIdmFlag = Boolean.parseBoolean(configs.getProperty("authIdmFlag"));
        if(authIdmFlag){//易和验证
            //1、对接易和用户名密码验证并返回令牌
            /*String resultStr = "";
            try {
                PortTypeParams params = new PortTypeParams();
                SimpleAuthService service = new SimpleAuthService();
                SimpleAuthServicePortType portType = service
                        .getSimpleAuthServiceHttpPort();
                resultStr = portType.idValidation(params.getServiceCode(),
                        params.getCurTimeStr(), params.getSign(), username,
                        orgcode, params.getEncryptiontype(), pwdStr,
                        params.getDatatype());
                if("".equals(resultStr)){
                    String msg = "单点登录失败易和验证失败：易和验证失败，请联系管理员！";
                    mycredential1.setPassword("");
                    throw new NoAuthException(msg);
                }else{
                    JSONObject verifyResult = JSON.parseObject(resultStr);
                    if (verifyResult.getString("result").equals("0")) {
                        //易和验证成功
                    }else{
                        mycredential1.setPassword("");
                        throw new NoAuthException("msg");
                    }
                }*/

            String pswBs64= CommonUtil.getFromBASE64(pwdStr);

            //--3个参数，1、sql 2、要传递的参数数组 3、返回来的对象class
            //获取组合name
            String nameConcat = (String) template.queryForObject(jdbcPros2.getSql(),new Object[] {orgcode,username,},java.lang.String.class);
            Prop prop = IDMConfig.getProp(IDM_KEY_GOV);
            IDMClient client = new IDMClient(prop);
            IdValidationResult idValiResult=client.idValidation(attributes.getRequest(), nameConcat, pswBs64);
            if (IDMClient.SUCCESS.equals(idValiResult.getResult())) {
                Log.info("易和用户登录成功");
                handlerResult = authOkResult(attributes,username,user,mycredential1);
            }else {
                String msg = "单点登录失败易和验证失败：易和验证失败，请联系管理员！";
                Log.info(msg);
                mycredential1.setPassword("");
                throw new NoAuthException(msg);
            }
        }else{//本地验证
            //给数据进行md5加密
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(pwdStr.toString().getBytes());
            String pwd = new BigInteger(1, md.digest()).toString(16);
            //BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            //if(encoder.matches(pwd,user.get("localpwd").toString())){
            if(pwd.equals(user.get(jdbcPros.getFieldPassword()).toString())){
                handlerResult = authOkResult(attributes,username,user,mycredential1);
            }else{
                mycredential1.setPassword("");
                throw new FailedLoginException("用户名或密码不正确");
            }
        }
        return handlerResult;
    }

    public AuthenticationHandlerExecutionResult authOkResult(ServletRequestAttributes attributes,String username,Map<String,Object> user,UsernamePasswordCaptchaCredential mycredential1){
        HttpSession session = attributes.getRequest().getSession();
        session.setAttribute("username", username);
        //返回多属性
        JdbcPrincipalAttributesProperties jdbcAttrs = casProperties.getAuthn().getAttributeRepository().getJdbc().get(0);
        Map<String, Object> map=new HashMap<>();
        Map<String, String> attrMap =  jdbcAttrs.getAttributes();
        for (String str : attrMap.keySet()) {
            //map.keySet()返回的是所有key的值
            String val = (String)attrMap.get(str);//得到每个key多对用value的值
            map.put(val, user.get(val).toString());
        }
        return createHandlerResult(mycredential1, principalFactory.createPrincipal(username, map), null);
    }


    @Override
    public boolean supports(Credential credential) {
        return credential instanceof UsernamePasswordCaptchaCredential;
    }
}