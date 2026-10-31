
[shader("raygeneration")]
void main()
{
    HitObject hit = HitObject.MakeNop();
    ReorderThread(hit, 0u, 0u);
}
