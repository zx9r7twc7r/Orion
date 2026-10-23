// Standalone Vulkan EXT SER compiler probe.
// This deliberately uses a NOP HitObject so it has no scene or descriptor ABI.

[shader("raygeneration")]
void main()
{
    HitObject hit = HitObject.MakeNop();
    ReorderThread(hit, 0u, 0u);
}
