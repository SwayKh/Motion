(async function() {
  try {
    const res = await fetch('/api/auth/me', { credentials: 'include' });
    if (res.status === 401) {
      window.location.href = '/login.html';
      return;
    }
  } catch (e) {
    window.location.href = '/login.html';
  }
})();
