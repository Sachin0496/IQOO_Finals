# AI4Bharat OpenHands SL-GCN (INCLUDE, 263 ISL signs) -> ONNX. Their code and checkpoint; we only export.
import csv, json, numpy as np, torch, yaml
from loadck import load_state
from ohpkg.decoupled_gcn import DecoupledGCN
from ohpkg.fc import FC
cfg = yaml.safe_load(open("slgcn/include/sl_gcn/config.yaml"))
from omegaconf import OmegaConf
enc_params = {k: OmegaConf.create(v) if isinstance(v, dict) else v for k, v in cfg["model"]["encoder"]["params"].items()}
class Net(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.encoder = DecoupledGCN(in_channels=2, **enc_params)
        self.decoder = FC(n_features=self.encoder.n_out_features, num_class=263, dropout_ratio=0)
    def forward(self, x):  # x: (1, 2, T, 27) normalised keypoints
        return torch.softmax(self.decoder(self.encoder(x, 1.0)), -1)
net = Net().eval()
sd = {k[len("model."):]: v for k, v in load_state("slgcn/include/sl_gcn/epoch=112-step=12203.ckpt").items() if k.startswith("model.")}
missing, unexpected = net.load_state_dict(sd, strict=False)
print("missing", missing, "unexpected", unexpected)
x = torch.randn(1, 2, 40, 27) * 0.3
with torch.no_grad():
    y = net(x)
torch.onnx.export(net, (x,), "isl_include_slgcn.onnx", input_names=["keypoints"], output_names=["probs"],
                  dynamic_axes={"keypoints": {2: "frames"}}, opset_version=17, dynamo=False)
import onnxruntime as ort
s = ort.InferenceSession("isl_include_slgcn.onnx", providers=["CPUExecutionProvider"])
for t in (40, 25, 64):
    xt = torch.randn(1, 2, t, 27) * 0.3
    with torch.no_grad():
        ref = net(xt).numpy()
    out = s.run(None, {"keypoints": xt.numpy()})[0]
    print("T", t, "max abs diff", float(np.abs(out - ref).max()))
labels = sorted({r["Word"] for r in csv.DictReader(open("meta/Train_Test_Split/train_include.csv"))})
json.dump(labels, open("isl_include_labels.json", "w"), indent=0)
import os; print("onnx MB", round(os.path.getsize("isl_include_slgcn.onnx") / 1e6, 1), "labels", len(labels))
