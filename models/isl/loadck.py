import pickle, torch
class _Stub:
    def __init__(self, *a, **k): pass
    def __setstate__(self, s): self.__dict__["state"] = s
class _Unpickler(pickle.Unpickler):
    def find_class(self, mod, name):
        try:
            return super().find_class(mod, name)
        except Exception:
            return _Stub
class _PM:
    Unpickler = _Unpickler
    load = pickle.load
def load_state(path):
    ck = torch.load(path, map_location="cpu", pickle_module=_PM, weights_only=False)
    return ck["state_dict"]
