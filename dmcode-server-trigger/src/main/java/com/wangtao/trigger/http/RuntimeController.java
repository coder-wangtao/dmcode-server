package com.wangtao.trigger.http;

import com.wangtao.api.response.Response;
import com.wangtao.types.enums.ResponseCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/runtime")
//在 local（本地开发）环境下，提供一个 HTTP 接口，用来检查应用是否正常运行，并返回当前运行环境、工作目录和进程 ID 等信息。
public class RuntimeController {

    @Value("${spring.profiles.active:local}")
    private String profile;

    //这里的 user.home 是 JVM 提供的系统属性，表示当前操作系统用户的主目录。
    @Value("${dmcode.local.workspace:${user.home}}")
    private String workspace;

    @GetMapping("/health")
    public Response<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "ready");
        data.put("profile", profile);
        data.put("workspace", workspace);
        data.put("pid", ProcessHandle.current().pid());
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }
}
