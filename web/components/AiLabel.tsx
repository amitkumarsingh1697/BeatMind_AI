import { SparkleIcon } from "./Icons";

/** The visible "AI-generated" label (REQ-F-33, India IT Rules). Never hidden or shrunk away. */
export default function AiLabel({ text = "AI-generated" }: { text?: string }) {
  return (
    <span className="badge badge-ai">
      <SparkleIcon size={14} />
      {text}
    </span>
  );
}
