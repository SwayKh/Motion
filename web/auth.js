function getCsrfToken() {
  const match = document.cookie.match(/(?:^|; )motion_csrf=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : "";
}

async function apiFetch(url, options = {}) {
  options.credentials = options.credentials || "include";
  options.headers = options.headers || {};
  if (options.method && options.method !== "GET" && options.method !== "HEAD") {
    const csrf = getCsrfToken();
    if (csrf) {
      options.headers["X-CSRF-Token"] = csrf;
    }
  }
  if (options.body && typeof options.body === "object" && !(options.body instanceof FormData)) {
    options.headers["Content-Type"] = options.headers["Content-Type"] || "application/json";
    options.body = JSON.stringify(options.body);
  }
  return fetch(url, options);
}

// Register
const registerForm = document.getElementById("registerForm");
if (registerForm) {
  registerForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const email = document.getElementById("email").value;
    const password = document.getElementById("password").value;
    const passwordConfirm = document.getElementById("passwordConfirm").value;
    const errorDiv = document.getElementById("error");
    errorDiv.textContent = "";
    if (password !== passwordConfirm) {
      errorDiv.textContent = "Passwords do not match";
      return;
    }
    try {
      const res = await apiFetch("/api/auth/register", {
        method: "POST",
        body: { email, password, passwordConfirm },
      });
      const data = await res.json();
      if (!res.ok) {
        errorDiv.textContent = data.error || "Registration failed";
        return;
      }
      window.location.href = "/app.html";
    } catch (err) {
      errorDiv.textContent = "Registration failed";
    }
  });
}

// Login
const loginForm = document.getElementById("loginForm");
if (loginForm) {
  loginForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const email = document.getElementById("email").value;
    const password = document.getElementById("password").value;
    const errorDiv = document.getElementById("error");
    errorDiv.textContent = "";
    try {
      const res = await apiFetch("/api/auth/login", {
        method: "POST",
        body: { email, password },
      });
      const data = await res.json();
      if (!res.ok) {
        errorDiv.textContent = data.error || "Login failed";
        return;
      }
      window.location.href = "/app.html";
    } catch (err) {
      errorDiv.textContent = "Login failed";
    }
  });
}

// Password reset request
const resetReqForm = document.getElementById("resetReqForm");
if (resetReqForm) {
  resetReqForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const email = document.getElementById("email").value;
    const msg = document.getElementById("msg");
    msg.textContent = "";
    try {
      const res = await apiFetch("/api/auth/password/reset/request", {
        method: "POST",
        body: { email },
      });
      await res.json();
      msg.style.color = "var(--accent)";
      msg.textContent = "If an account exists, a reset request was processed.";
    } catch (err) {
      msg.style.color = "var(--danger)";
      msg.textContent = "Request failed";
    }
  });
}

// Password reset
const resetForm = document.getElementById("resetForm");
if (resetForm) {
  const params = new URLSearchParams(window.location.search);
  const token = params.get("token");
  if (token) {
    document.getElementById("token").value = token;
  }
  resetForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const tokenVal = document.getElementById("token").value;
    const newPassword = document.getElementById("newPassword").value;
    const newPasswordConfirm = document.getElementById("newPasswordConfirm").value;
    const errorDiv = document.getElementById("error");
    errorDiv.textContent = "";
    if (newPassword !== newPasswordConfirm) {
      errorDiv.textContent = "Passwords do not match";
      return;
    }
    try {
      const res = await apiFetch("/api/auth/password/reset", {
        method: "POST",
        body: { token: tokenVal, newPassword, newPasswordConfirm },
      });
      const data = await res.json();
      if (!res.ok) {
        errorDiv.textContent = data.error || "Reset failed";
        return;
      }
      window.location.href = "/login.html";
    } catch (err) {
      errorDiv.textContent = "Reset failed";
    }
  });
}

// Account page
const changePassForm = document.getElementById("changePassForm");
if (changePassForm) {
  changePassForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const currentPassword = document.getElementById("currentPassword").value;
    const newPassword = document.getElementById("newPassword").value;
    const newPasswordConfirm = document.getElementById("newPasswordConfirm").value;
    const msg = document.getElementById("passMsg");
    msg.textContent = "";
    if (newPassword !== newPasswordConfirm) {
      msg.style.color = "var(--danger)";
      msg.textContent = "Passwords do not match";
      return;
    }
    try {
      const res = await apiFetch("/api/auth/password/change", {
        method: "POST",
        body: { currentPassword, newPassword, newPasswordConfirm },
      });
      const data = await res.json();
      if (!res.ok) {
        msg.style.color = "var(--danger)";
        msg.textContent = data.error || "Failed to change password";
        return;
      }
      msg.style.color = "var(--accent)";
      msg.textContent = "Password changed";
      changePassForm.reset();
    } catch (err) {
      msg.style.color = "var(--danger)";
      msg.textContent = "Failed to change password";
    }
  });
}

// Load user info on account page
async function loadUserInfo() {
  try {
    const res = await apiFetch("/api/auth/me");
    if (res.ok) {
      const data = await res.json();
      const emailEl = document.getElementById("userEmail");
      if (emailEl && data.user) {
        emailEl.textContent = data.user.email;
      }
      const userInfo = document.getElementById("userInfo");
      if (userInfo && data.user) {
        userInfo.textContent = data.user.email;
      }
    }
  } catch (e) {}
}
loadUserInfo();

// Logout buttons
async function doLogout() {
  try {
    await apiFetch("/api/auth/logout", { method: "POST" });
  } catch (e) {}
  window.location.href = "/landing.html";
}
const logoutBtn = document.getElementById("logoutBtn");
if (logoutBtn) logoutBtn.addEventListener("click", doLogout);
const logoutBtnTop = document.getElementById("logoutBtnTop");
if (logoutBtnTop) logoutBtnTop.addEventListener("click", doLogout);
