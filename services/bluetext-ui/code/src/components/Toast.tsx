export function Toast({
  toast,
}: {
  toast: { message: string; type: "success" | "error" } | null;
}) {
  return (
    <div className={`toast ${toast ? "show" : ""} ${toast?.type ?? ""}`}>
      {toast?.message}
    </div>
  );
}
