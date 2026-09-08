const TOKEN_KEY = "sarab.admin.session";
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export const tokenStore = {
  get: () => sessionStorage.getItem(TOKEN_KEY),
  set: (token: string) => sessionStorage.setItem(TOKEN_KEY, token),
  clear: () => sessionStorage.removeItem(TOKEN_KEY),
};
export async function api<T>(
  path: string,
  options: RequestInit = {},
  token = tokenStore.get(),
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (options.body) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const deadline = AbortSignal.timeout(15_000);
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      ...options,
      headers,
      credentials: "omit",
      cache: "no-store",
      signal: options.signal
        ? AbortSignal.any([options.signal, deadline])
        : deadline,
    });
  } catch (error) {
    if (deadline.aborted)
      throw new ApiError(
        0,
        "استغرق الخادم وقتًا طويلًا للاستجابة. أعد المحاولة.",
      );
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new ApiError(
      0,
      "تعذّر الاتصال بالخادم. تحقق من الشبكة ثم أعد المحاولة.",
    );
  }
  if (!response.ok) {
    if (response.status === 401)
      throw new ApiError(
        401,
        path === "/auth/login"
          ? "اسم المستخدم أو كلمة المرور غير صحيحة."
          : "انتهت الجلسة. سجّل الدخول مرة أخرى.",
      );
    if (response.status === 429)
      throw new ApiError(
        429,
        "محاولات كثيرة خلال وقت قصير. انتظر قليلًا ثم حاول مجددًا.",
      );
    let message = "";
    try {
      const body = await response.json();
      message = body.message || body.error || "";
    } catch {
      /* status fallback */
    }
    const readableArabic =
      typeof message === "string" && /[\u0600-\u06ff]/.test(message);
    throw new ApiError(
      response.status,
      readableArabic
        ? message
        : response.status === 403
          ? "ليس لديك صلاحية لتنفيذ هذا الإجراء."
          : response.status === 400
            ? "تحقق من البيانات المدخلة ثم أعد المحاولة."
            : "تعذّر إتمام الطلب. أعد المحاولة بعد قليل.",
    );
  }
  return response.status === 204
    ? (undefined as T)
    : (response.json() as Promise<T>);
}
