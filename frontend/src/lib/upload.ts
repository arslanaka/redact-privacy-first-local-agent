export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
export const SUPPORTED_EXTENSIONS = ["pdf", "txt", "md", "markdown"];

/** Client-side pre-check for a friendlier UX. The backend validates again (type, size and content). */
export function validateFile(file: { name: string; size: number }): string | null {
  const ext = file.name.includes(".") ? file.name.split(".").pop()!.toLowerCase() : "";
  if (!SUPPORTED_EXTENSIONS.includes(ext)) {
    return "Unsupported file type. Please upload a PDF, TXT or Markdown file.";
  }
  if (file.size === 0) {
    return "The file is empty.";
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return "The file is larger than the 10 MB limit.";
  }
  return null;
}
