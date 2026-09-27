package com.dycomment.tv;

import org.json.JSONObject;

/** Independent account validation shared by the LAN receiver and API21 fixtures. */
final class OfficialLoginPolicy {
    static final String SELF_PATH = "/aweme/v1/web/user/profile/self/";

    static void verified(JSONObject result) throws Exception {
        JSONObject user = result.optJSONObject("user");
        if (!(result.opt("status_code") instanceof Number)
                || ((Number) result.opt("status_code")).doubleValue() != 0d || user == null
                || !user.optString("uid").matches("[1-9][0-9]*"))
            throw new Exception("新账号未通过独立验证；现有账号已保留");
    }
}
