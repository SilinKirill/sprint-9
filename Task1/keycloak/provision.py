"""Apply the checked-in realm to a running Keycloak without deleting its database."""
import json
import os
import re
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode, quote
from urllib.request import Request, urlopen

BASE = os.getenv("KEYCLOAK_URL", "http://keycloak:8080").rstrip("/")
TOKEN = ""
REALM = "/admin/realms/reports-realm"


def call(method, path, payload=None, form=False, allow_missing=False):
    headers = {}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    if payload is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = (urlencode(payload) if form else json.dumps(payload)).encode()
    else:
        data = None
    request = Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=30) as response:
            body = response.read()
            if body:
                return json.loads(body)
            location = response.headers.get("Location")
            return {"id": location.rstrip("/").rsplit("/", 1)[-1]} if location else None
    except HTTPError as error:
        if error.code == 404 and allow_missing:
            return None
        # Avoid exposing admin tokens, passwords, and returned credential bodies.
        raise RuntimeError(f"Keycloak {method} {path}: HTTP {error.code}") from None


def expand(value):
    if isinstance(value, dict):
        return {key: expand(item) for key, item in value.items()}
    if isinstance(value, list):
        return [expand(item) for item in value]
    if isinstance(value, str):
        def replace(match):
            name = match.group(1)
            result = os.getenv(name, "")
            if not result and name in ("BIONICPRO_CLIENT_SECRET", "LDAP_ADMIN_PASSWORD"):
                raise RuntimeError(f"Required environment variable {name} is missing")
            return result
        return re.sub(r"\$\{([A-Z_]+)\}", replace, value)
    return value


def apply_flows(flows):
    known = {flow["alias"]: flow for flow in call("GET", REALM + "/authentication/flows")}
    # The list endpoint omits nested flows. Discover their IDs through executions.
    for root in list(known.values()):
        for execution in call("GET", REALM + "/authentication/flows/" + quote(root["alias"]) + "/executions"):
            if execution.get("authenticationFlow"):
                known[execution["displayName"]] = {"id": execution["flowId"]}
    for flow in flows:
        if flow["alias"] not in known:
            known[flow["alias"]] = call("POST", REALM + "/authentication/flows",
                {key: value for key, value in flow.items() if key != "authenticationExecutions"})
    for flow in flows:
        alias = flow["alias"]
        endpoint = REALM + "/authentication/flows/" + quote(alias) + "/executions"
        executions = [item for item in call("GET", endpoint) if item["level"] == 0]
        for item in flow["authenticationExecutions"]:
            child = known[item["flowAlias"]]["id"] if item.get("authenticatorFlow") else None
            found = next((existing for existing in executions
                          if (existing.get("flowId") == child if child else
                              existing.get("providerId") == item["authenticator"])), None)
            if found:
                call("PUT", endpoint, {"id": found["id"], "requirement": item["requirement"]})
            else:
                execution = {"parentFlow": known[alias]["id"], "requirement": item["requirement"],
                             "authenticatorFlow": bool(child)}
                if child:
                    execution["flowId"] = child
                else:
                    execution["authenticator"] = item["authenticator"]
                call("POST", REALM + "/authentication/executions", execution)


def apply_component(component, parent, provider_type):
    endpoint = REALM + "/components"
    existing = next((item for item in call("GET", endpoint + "?" + urlencode({"parent": parent}))
                     if item["name"] == component["name"]
                     and item["providerId"] == component["providerId"]), None)
    model = {key: value for key, value in component.items() if key != "subComponents"}
    model["parentId"] = parent
    model["providerType"] = provider_type
    if existing:
        model["id"] = existing["id"]
        call("PUT", endpoint + "/" + existing["id"], model)
    else:
        call("POST", endpoint, model)
    saved = next(item for item in call("GET", endpoint + "?" + urlencode({"parent": parent}))
                 if item["name"] == model["name"] and item["providerId"] == model["providerId"])
    for child_type, children in component.get("subComponents", {}).items():
        for child in children:
            apply_component(child, saved["id"], child_type)
    return saved["id"]


def main():
    global TOKEN
    config = expand(json.loads(Path(__file__).with_name("keycloak-results-export.json").read_text(encoding="utf-8")))
    enabled = os.getenv("YANDEX_ENABLED", "false").lower() == "true"
    provider = config["identityProviders"][0]
    if enabled and not all(provider["config"][key] for key in ("clientId", "clientSecret")):
        raise RuntimeError("YANDEX_ENABLED=true requires YANDEX_CLIENT_ID and YANDEX_CLIENT_SECRET")
    provider["enabled"] = enabled

    TOKEN = call("POST", "/realms/master/protocol/openid-connect/token", {
        "grant_type": "password", "client_id": "admin-cli",
        "username": os.getenv("KEYCLOAK_ADMIN", "admin"),
        "password": os.environ["KEYCLOAK_ADMIN_PASSWORD"]}, form=True)["access_token"]
    current = call("GET", REALM, allow_missing=True)
    if current is None:
        call("POST", "/admin/realms", config)
        current = call("GET", REALM)

    apply_flows(config["authenticationFlows"])
    for role in config["roles"]["realm"]:
        if call("GET", REALM + "/roles/" + quote(role["name"]), allow_missing=True) is None:
            call("POST", REALM + "/roles", role)
    for client in config["clients"]:
        existing = call("GET", REALM + "/clients?" + urlencode({"clientId": client["clientId"]}))
        if existing:
            call("PUT", REALM + "/clients/" + existing[0]["id"], client)
        else:
            call("POST", REALM + "/clients", client)
    for action in config["requiredActions"]:
        call("PUT", REALM + "/authentication/required-actions/" + action["alias"], action)
    for provider in config["identityProviders"]:
        endpoint = REALM + "/identity-provider/instances/" + provider["alias"]
        if call("GET", endpoint, allow_missing=True):
            call("PUT", endpoint, provider)
        else:
            call("POST", REALM + "/identity-provider/instances", provider)

    ldap_id = apply_component(config["components"]["org.keycloak.storage.UserStorageProvider"][0], current["id"],
                              "org.keycloak.storage.UserStorageProvider")
    sync = call("POST", REALM + "/user-storage/" + ldap_id + "/sync?action=triggerFullSync")
    if sync.get("failed", 0):
        raise RuntimeError("LDAP synchronization reported failed users")

    # Preserve existing passwords and enrolled OTP. Add missing demo users only.
    for user in config["users"]:
        matches = call("GET", REALM + "/users?" + urlencode({"username": user["username"], "exact": "true"}))
        if not matches:
            call("POST", REALM + "/users", user)
            matches = call("GET", REALM + "/users?" + urlencode({"username": user["username"], "exact": "true"}))
        saved = matches[0]
        credentials = call("GET", REALM + "/users/" + saved["id"] + "/credentials")
        if not any(credential["type"] == "otp" for credential in credentials):
            actions = list(dict.fromkeys(saved.get("requiredActions", []) + ["CONFIGURE_TOTP"]))
            call("PUT", REALM + "/users/" + saved["id"], {"requiredActions": actions})
        roles = [call("GET", REALM + "/roles/" + quote(role)) for role in user["realmRoles"]]
        call("POST", REALM + "/users/" + saved["id"] + "/role-mappings/realm", roles)

    collections = {"users", "clients", "roles", "components", "authenticationFlows",
                   "requiredActions", "identityProviders"}
    call("PUT", REALM, {key: value for key, value in config.items() if key not in collections})
    # Invalidate sessions made before MFA became mandatory.
    call("POST", REALM + "/logout-all")
    print("Applied reports-realm: BFF + PKCE, mandatory OTP, LDAP roles.")
    print("Yandex ID enabled." if enabled else "Yandex ID ready; disabled until credentials are provided.")
    print("Existing users and OTP preserved; previous sign-in sessions closed.")


if __name__ == "__main__":
    main()
