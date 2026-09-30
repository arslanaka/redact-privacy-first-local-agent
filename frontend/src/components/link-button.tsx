import Link from "next/link";
import { Button } from "@/components/ui/button";

type Props = Omit<React.ComponentProps<typeof Button>, "render" | "nativeButton"> & { href: string };

/** A Button that navigates. Base UI buttons need nativeButton={false} when rendered as a link. */
export function LinkButton({ href, children, ...props }: Props) {
  return (
    <Button nativeButton={false} render={<Link href={href} />} {...props}>
      {children}
    </Button>
  );
}
